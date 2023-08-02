/*
 * Copyright (c) 2023 Hydrolix Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hydrolix.spark.connector.partitionreader

import io.hydrolix.spark.connector.{HdxScanPartition, TurbineIni, spawn}
import io.hydrolix.spark.model._

import com.google.common.io.{ByteStreams, MoreFiles}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.HdxPushdown
import org.apache.spark.sql.connector.read.PartitionReader

import java.io._
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import scala.collection.mutable
import scala.sys.process.{Process, ProcessIO}
import scala.util.Using.resource
import scala.util.control.Breaks.{break, breakable}
import scala.util.{Try, Using}

object HdxPartitionReader extends Logging {
  /**
   * This is done early before any work needs to be done because of https://bugs.openjdk.org/browse/JDK-8068370 -- we
   * spawn child processes too fast and they accidentally clobber each other's temp files
   *
   * TODO try not to recreate these files every time if they're unchanged... Maybe name them according to a git hash
   *  or a sha256sum of the contents?
   */
  private lazy val turbineCmdTmp = {
    val f = File.createTempFile("turbine_cmd_", ".exe")
    f.deleteOnExit()

    Using.Manager { use =>
      ByteStreams.copy(
        use(getClass.getResourceAsStream("/linux-x86-64/turbine_cmd")),
        use(new FileOutputStream(f))
      )
    }.get

    f.setExecutable(true)

    spawn(f.getAbsolutePath) match {
      case (255, "", "No command specified") => // OK
      case (exit, out, err) =>
        log.warn(s"turbine_cmd may not work on this OS, it exited with code $exit, stdout: $out, stderr: $err")
    }

    log.info(s"Extracted turbine_cmd binary to ${f.getAbsolutePath}")

    f
  }

  private lazy val hdxFsTmp = {
    val path = Files.createTempDirectory("hdxfs")
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        log.info(s"Deleting hdxfs tmp directory $path")
        MoreFiles.deleteRecursively(path)
      }
    })
    path.toFile
  }

  /**
   * Read lines from `stream`, calling `onLine` when a line is read, then `onDone` when EOF is reached.
   * Must be called in its own thread, because it does blocking reads!
   *
   * Closes the stream.
   */
  private def readLines(stream: InputStream, onLine: String => Unit, onDone: => Unit): Unit = {
    Using.resource(new BufferedReader(new InputStreamReader(stream, "UTF-8"))) { reader =>
      breakable {
        while (true) {
          val line = reader.readLine()
          try {
            if (line == null) {
              onDone
              break()
            } else {
              onLine(line)
            }
          } catch {
            case _: InterruptedException =>
              // If we got killed early (e.g. because of a LIMIT) let's be quiet about it
              Thread.currentThread().interrupt()
              break()
          }
        }
      }
    }
  }

  private val stderrFilterR = """^read hdx_partition=(.*?) rows=(.*?) values=(.*?) in (.*?)$""".r
}

trait HdxPartitionReader[T <: AnyRef]
  extends PartitionReader[T]
    with Logging
{
  val info: HdxConnectionInfo
  val storage: HdxStorageSettings
  val primaryKeyName: String
  val scan: HdxScanPartition

  protected val doneSignal: T
  protected def outputFormat: String
  protected def handleStdout(stdout: InputStream): Unit

  import HdxPartitionReader._

  protected val stdoutQueue = new ArrayBlockingQueue[T](1024)
  // Cache because PartitionReader says get() should always return the same record if called multiple times per next()
  @volatile private var data: T = _
  @volatile private var recordsEmitted = 0
  protected val expectedLines = new AtomicLong(1) // 1, not 0, to make room for doneSignal
  private val stderrLines = mutable.ListBuffer[String]()

  // This is done early so it can crash before creating temp files etc.
  private val schema = scan.schema.fields.map { fld =>
    HdxOutputColumn(fld.name, scan.hdxCols.getOrElse(fld.name, sys.error(s"No HdxColumnInfo for ${fld.name}")).hdxType)
  }

  private val turbineIniBefore = TurbineIni(storage, info.cloudCred1, info.cloudCred2, hdxFsTmp)

  protected lazy val (turbineIniAfter, credsTempFile) = if (storage.cloud == "gcp") {
    val gcsKeyFile = File.createTempFile("turbine_gcs_key_", ".json")
    gcsKeyFile.deleteOnExit()

    val turbineIni = Using.Manager { use =>
      // For gcs, cloudCred1 is a base64(gzip(gcs_service_account_key.json)) and cloudCred2 is unused
      val gcsKeyB64 = Base64.getDecoder.decode(info.cloudCred1)

      val gcsKeyBytes = ByteStreams.toByteArray(use(new GZIPInputStream(new ByteArrayInputStream(gcsKeyB64))))
      use(new FileOutputStream(gcsKeyFile)).write(gcsKeyBytes)

      val turbineIniWithGcsCredsPath = turbineIniBefore.replace("%CREDS_FILE%", gcsKeyFile.getAbsolutePath)

      turbineIniWithGcsCredsPath
    }.get

    (turbineIni, Some(gcsKeyFile))
  } else {
    // AWS doesn't need any further munging of turbine.ini
    (turbineIniBefore, None)
  }

  // TODO don't create a duplicate file per partition, use a content hash or something
  protected lazy val turbineIniTmp = File.createTempFile("turbine_ini_", ".ini")
  turbineIniTmp.deleteOnExit()
  resource(new FileOutputStream(turbineIniTmp)) {
    _.write(turbineIniAfter.getBytes("UTF-8"))
  }

  // TODO does anything need to be quoted here?
  //  Note, this relies on a bunch of changes in hdx_reader that may not have been merged to turbine/turbine-core yet,
  //  see https://hydrolix.atlassian.net/browse/HDX-3779
  protected val hdxReaderProcess = {
    val schemaStr = JSON.objectMapper.writeValueAsString(schema)

    val exprArgs = {
      val renderedPreds = scan.pushed.flatMap { predicate =>
        HdxPushdown.renderHdxFilterExpr(predicate, primaryKeyName, scan.hdxCols)
      }

      if (renderedPreds.isEmpty) Nil else {
        List(
          "--expr",
          renderedPreds.mkString("[", " AND ", "]")
        )
      }
    }

    val turbineCmdArgs = List(
      "hdx_reader",
      "--config", turbineIniTmp.getAbsolutePath,
      "--output_format", outputFormat,
      "--output_path", "-",
      "--hdx_partition", s"${scan.path}",
      "--schema", schemaStr
    ) ++ exprArgs
    log.info(s"Running ${turbineCmdTmp.getAbsolutePath} ${turbineCmdArgs.mkString(" ")}")

    Process(
      turbineCmdTmp.getAbsolutePath,
      turbineCmdArgs
    ).run(
      new ProcessIO(
        {
          _.close()
        }, // Don't care about stdin
        handleStdout,
        { stderr =>
          readLines(stderr,
            {
              case stderrFilterR(_*) => () // Ignore expected output
              case l => stderrLines += l // Capture unexpected output
            },
            () // No need to do anything special when stderr drains
          )
        }
      )
    )
  }

  override def next(): Boolean = {
    val got = try {
      stdoutQueue.take() // It's OK to block here, we'll always have doneSignal...
    } catch {
      case _: InterruptedException =>
        // ...But if we got killed while waiting, don't be noisy about it
        Thread.currentThread().interrupt()
        return false
    }

    if (got eq doneSignal) {
      // There are definitely no more records, stdout is closed, now we can wait for the sweet release of death
      val exit = hdxReaderProcess.exitValue()
      val err = stderrLines.mkString("\n  ", "\n  ", "\n")
      if (exit != 0) {
        throw new RuntimeException(s"turbine_cmd process exited with code $exit; stderr was: $err")
      } else {
        if (err.trim.nonEmpty) log.warn(s"turbine_cmd process exited with code $exit but stderr was: $err")
        false
      }
    } else {
      this.data = got

      recordsEmitted += 1

      expectedLines.decrementAndGet() > 0
    }
  }

  override def get(): T = data

  override def close(): Unit = {
    log.info(s"$recordsEmitted records read from ${scan.path}")
    Try(turbineIniTmp.delete())
    credsTempFile.foreach(f => Try(f.delete()))
  }
}
