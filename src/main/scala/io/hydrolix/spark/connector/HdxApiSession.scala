package io.hydrolix.spark.connector

import io.hydrolix.spark.model._

import com.github.benmanes.caffeine.cache.{CacheLoader, Caffeine, Expiry, LoadingCache}
import org.apache.hc.client5.http.classic.methods.{HttpGet, HttpPost}
import org.apache.hc.client5.http.impl.classic.{BasicHttpClientResponseHandler, HttpClients}
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.StringEntity
import org.apache.spark.sql.catalyst.analysis.{NoSuchDatabaseException, NoSuchTableException}

import java.time.Duration
import java.util.UUID

class HdxApiSession(info: HdxConnectionInfo) {
  def tables(db: String): List[HdxApiTable] = {
    val project = database(db).getOrElse(throw NoSuchDatabaseException(db))
    allTablesCache.get(project.uuid)
  }

  def databases(): List[HdxProject] = {
    allProjectsCache.get(0)
  }

  def storages(): List[HdxStorage] = {
    allStoragesCache.get(0)
  }

  private def database(db: String): Option[HdxProject] = {
    databases().findSingle(_.name == db)
  }

  def table(db: String, table: String): Option[HdxApiTable] = {
    val project = database(db).getOrElse(throw NoSuchDatabaseException(db))
    allTablesCache.get(project.uuid).findSingle(_.name == table)
  }

  private def views(db: String, table: String): List[HdxView] = {
    val tbl = this.table(db, table).getOrElse(throw NoSuchTableException(table))
    allViewsByTableCache.get(tbl.project -> tbl.uuid)
  }

  def pk(db: String, table: String): HdxOutputColumn = {
    val vs = views(db, table)
    val pkCandidates = vs.filter(_.settings.isDefault).flatMap { view =>
      view.settings.outputColumns.find { col =>
        col.datatype.primary && (col.datatype.`type` == HdxValueType.DateTime || col.datatype.`type` == HdxValueType.DateTime64)
      }
    }

    pkCandidates match {
      case List(one) => one
      case Nil => sys.error(s"Couldn't find a primary key for $db.$table")
      case other => sys.error(s"Found multiple candidate primary keys for $db.$table")
    }
  }

  private val client = HttpClients.createDefault()

  // It's a bit silly to have a one-element cache here, but we want the auto-renewal
  // TODO this is Integer for stupid Scala 2.12 reasons; it should be Unit. Always pass 0!
  private val tokenCache: LoadingCache[Integer, HdxLoginRespAuthToken] = {
    Caffeine.newBuilder()
      .expireAfter(new Expiry[Integer, HdxLoginRespAuthToken]() {
        private def when(value: HdxLoginRespAuthToken): Long = {
          System.currentTimeMillis() + value.expiresIn - 600 // Renew 10 minutes before expiry
        }

        override def expireAfterCreate(key: Integer,
                                       value: HdxLoginRespAuthToken,
                                       currentTime: Long): Long =
          when(value)

        override def expireAfterUpdate(key: Integer,
                                       value: HdxLoginRespAuthToken,
                                       currentTime: Long,
                                       currentDuration: Long): Long =
          when(value)

        override def expireAfterRead(key: Integer, value: HdxLoginRespAuthToken, currentTime: Long, currentDuration: Long): Long =
          Long.MaxValue
      })
      .build((_: Integer) => {
        val loginPost = new HttpPost(info.apiUrl.resolve("login"))
        loginPost.setEntity(new StringEntity(
          JSON.objectMapper.writeValueAsString(HdxLoginRequest(info.user, info.password)),
          ContentType.APPLICATION_JSON
        ))
        val loginRespS = client.execute(loginPost, new BasicHttpClientResponseHandler())

        val loginRespBody = JSON.objectMapper.readValue[HdxLoginResponse](loginRespS)
        loginRespBody.authToken
      })
  }

  // It's a bit silly to have a one-element cache here, but there's no backend API to find projects by name
  // TODO this is Integer for stupid Scala 2.12 reasons; it should be Unit. Always pass 0!
  private val allProjectsCache: LoadingCache[Integer, List[HdxProject]] = {
    Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofHours(1))
      .build((_: Integer) => {
        val projectGet = new HttpGet(info.apiUrl.resolve(s"orgs/${info.orgId}/projects/"))
        projectGet.addHeader("Authorization", s"Bearer ${tokenCache.get(0).accessToken}")

        val projectResp = client.execute(projectGet, new BasicHttpClientResponseHandler())

        JSON.objectMapper.readValue[List[HdxProject]](projectResp)
      })
  }

  private val allTablesCache: LoadingCache[UUID, List[HdxApiTable]] = {
    Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofHours(1))
      .build((key: UUID) => {
        val project = allProjectsCache.get(0).find(_.uuid == key).getOrElse(throw NoSuchDatabaseException(key.toString))

        val tablesGet = new HttpGet(info.apiUrl.resolve(s"orgs/${info.orgId}/projects/${project.uuid}/tables/"))
        tablesGet.addHeader("Authorization", s"Bearer ${tokenCache.get(0).accessToken}")

        val tablesResp = client.execute(tablesGet, new BasicHttpClientResponseHandler())

        JSON.objectMapper.readValue[List[HdxApiTable]](tablesResp)
      })
  }

  private val allStoragesCache: LoadingCache[Integer, List[HdxStorage]] = {
    Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofHours(1))
      .build((_: Integer) => {
        val tablesGet = new HttpGet(info.apiUrl.resolve(s"orgs/${info.orgId}/storages/"))
        tablesGet.addHeader("Authorization", s"Bearer ${tokenCache.get(0).accessToken}")

        val storagesResp = client.execute(tablesGet, new BasicHttpClientResponseHandler())

        JSON.objectMapper.readValue[List[HdxStorage]](storagesResp)
      })
  }

  private val allViewsByTableCache: LoadingCache[(UUID, UUID), List[HdxView]] = {
    Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofHours(1))
      .build[(UUID, UUID), List[HdxView]](new CacheLoader[(UUID, UUID), List[HdxView]]() {
        override def load(key: (UUID, UUID)): List[HdxView] = {
          val (projectId, tableId) = key

          val viewsGet = new HttpGet(info.apiUrl.resolve(s"orgs/${info.orgId}/projects/$projectId/tables/$tableId/views/"))
          viewsGet.addHeader("Authorization", s"Bearer ${tokenCache.get(0).accessToken}")

          val viewsResp = client.execute(viewsGet, new BasicHttpClientResponseHandler())

          JSON.objectMapper.readValue[List[HdxView]](viewsResp)
        }
      })
  }
}