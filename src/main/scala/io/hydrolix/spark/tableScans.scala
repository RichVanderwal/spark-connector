package io.hydrolix.spark

import org.apache.spark.internal.Logging
import org.apache.spark.sql.HdxPredicatePushdown
import org.apache.spark.sql.connector.catalog._
import org.apache.spark.sql.connector.catalog.index.{SupportsIndex, TableIndex}
import org.apache.spark.sql.connector.expressions.filter.Predicate
import org.apache.spark.sql.connector.expressions.{Expressions, NamedReference}
import org.apache.spark.sql.connector.read._
import org.apache.spark.sql.types._
import org.apache.spark.sql.util.CaseInsensitiveStringMap

import java.util.Properties
import java.{util => ju}

case class HdxTable(info: HdxConnectionInfo,
                     api: HdxApiSession,
                    jdbc: HdxJdbcSession,
                   ident: Identifier,
                  schema: StructType,
                 options: CaseInsensitiveStringMap,
         primaryKeyField: String,
           shardKeyField: Option[String],
           sortKeyFields: List[String])
  extends Table
     with SupportsRead
     with SupportsIndex
{
  private val indices = Array(s"primary_$primaryKeyField") ++
    sortKeyFields.map(sk => s"sort_$sk").toArray ++
    shardKeyField.map(sk => s"shard_$sk").toArray

  override def name(): String = ident.toString

  override def capabilities(): ju.Set[TableCapability] = ju.EnumSet.of(TableCapability.BATCH_READ)

  override def newScanBuilder(options: CaseInsensitiveStringMap): ScanBuilder = {
    new HdxScanBuilder(info, api, jdbc, this)
  }

  override def createIndex(indexName: String,
                             columns: Array[NamedReference],
                   columnsProperties: ju.Map[NamedReference, ju.Map[String, String]],
                          properties: ju.Map[String, String])
                                    : Unit = nope()

  override def dropIndex(indexName: String): Unit = nope()

  override def indexExists(indexName: String): Boolean = {
    indices.contains(indexName)
  }

  override def listIndexes(): Array[TableIndex] = {
    indices.map { idxName =>
      val Array(idxType, fieldName) = idxName.split('_')

      new TableIndex(
        idxName,
        idxType,
        Array(Expressions.column(fieldName)),
        ju.Map.of(),
        new Properties()
      )
    }
  }
}

class HdxScanBuilder(info: HdxConnectionInfo,
                      api: HdxApiSession,
                     jdbc: HdxJdbcSession,
                    table: HdxTable)
  extends ScanBuilder
     with SupportsPushDownV2Filters
     with Logging
{
  private var pushed: List[Predicate] = List()

  override def pushPredicates(predicates: Array[Predicate]): Array[Predicate] = {
    val pushable = predicates.toList.groupBy(HdxPredicatePushdown.pushable(table.primaryKeyField, table.shardKeyField, _))

    val type1 = pushable.getOrElse(1, Nil)
    val type2 = pushable.getOrElse(2, Nil)
    val type3 = pushable.getOrElse(3, Nil)

    log.warn("These predicates are pushable: 1:[{}], 2:[{}]", type1, type2)
    log.warn("These predicates are NOT pushable: 3:[{}]", type3)

    // Types 1 & 2 will be pushed
    pushed = type1 ++ type2

    // Types 2 & 3 need to be evaluated after scanning
    (type2 ++ type3).toArray
  }

  override def pushedPredicates(): Array[Predicate] = {
    pushed.toArray
  }

  override def build(): Scan = {
    new HdxScan(info, api, jdbc, table, table.schema, pushed)
  }
}

class HdxScan(info: HdxConnectionInfo,
               api: HdxApiSession,
              jdbc: HdxJdbcSession,
             table: HdxTable,
              cols: StructType,
            pushed: List[Predicate])
  extends Scan
{
  override def toBatch: Batch = {
    new HdxBatch(info, api, jdbc, table, pushed)
  }

  override def description(): String = super.description()

  override def readSchema(): StructType = {
    table.schema.copy(
      fields = table.schema.fields.filter(f => cols.contains(f.name))
    )
  }
}

class HdxBatch(info: HdxConnectionInfo,
                api: HdxApiSession,
               jdbc: HdxJdbcSession,
              table: HdxTable,
             pushed: List[Predicate])
  extends Batch
{
  override def planInputPartitions(): Array[InputPartition] = {
    val db = table.ident.namespace().head
    val t = table.ident.name()

    val pk = api.pk(db, t)
    val parts = jdbc.collectPartitions(db, t)

    parts.flatMap { hp =>
      val max = hp.maxTimestamp
      val min = hp.minTimestamp
      val sk = hp.shardKey

      if (pushed.nonEmpty && pushed.forall(HdxPredicatePushdown.prunePartition(table.primaryKeyField, table.shardKeyField, _, min, max, sk))) {
        // All pushed predicates found this partition can be pruned; skip it
        None
      } else {
        // Either nothing was pushed, or at least one predicate didn't want to prune this partition; scan it
        Some(HdxPartition(db, t, hp.partition, pk.name, table.schema))
      }
    }.toArray
  }

  override def createReaderFactory(): PartitionReaderFactory = new HdxPartitionReaderFactory(info)
}