/*
 * Copyright (2020) The Hyperspace Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.hyperspace.index.sources.default

import java.util.Locale

import org.apache.hadoop.fs.FileStatus
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.execution.datasources.{FileFormat, HadoopFsRelation, LogicalRelation, PartitioningAwareFileIndex}
import org.apache.spark.sql.sources.DataSourceRegister
import org.apache.spark.sql.types.{DataType, StructType}

import com.microsoft.hyperspace.index.{Content, Hdfs, Relation}
import com.microsoft.hyperspace.index.sources.SourceProvider
import com.microsoft.hyperspace.util.HashingUtils

/**
 * Default implementation for file-based Spark built-in sources such as parquet, csv, json, etc.
 *
 * This source can support relations that meet the following criteria:
 *   - The relation is [[HadoopFsRelation]] with [[PartitioningAwareFileIndex]] as file index.
 *   - Its file format implements [[DataSourceRegister]].
 */
class DefaultFileBasedSource(private val spark: SparkSession) extends SourceProvider {
  private val supportedFormats = Set("avro", "csv", "json", "orc", "parquet", "text")

  /**
   * Creates [[Relation]] for IndexLogEntry using the given [[LogicalRelation]].
   *
   * @param logicalRelation logical relation to derive [[Relation]] from.
   * @return [[Relation]] object if the given 'logicalRelation' can be processed by this provider.
   *         Otherwise, None.
   */
  override def createRelation(logicalRelation: LogicalRelation): Option[Relation] = {
    logicalRelation.relation match {
      case HadoopFsRelation(
          location: PartitioningAwareFileIndex,
          _,
          dataSchema,
          _,
          fileFormat,
          options) if isSupportedFileFormat(fileFormat) =>
        val files = location.allFiles
        // Note that source files are currently fingerprinted when the optimized plan is
        // fingerprinted by LogicalPlanFingerprint.
        val sourceDataProperties = Hdfs.Properties(Content.fromLeafFiles(files))
        val fileFormatName = fileFormat.asInstanceOf[DataSourceRegister].shortName
        // "path" key in options can incur multiple data read unexpectedly.
        val opts = options - "path"
        Some(
          Relation(
            location.rootPaths.map(_.toString),
            Hdfs(sourceDataProperties),
            dataSchema.json,
            fileFormatName,
            opts))
      case _ => None
    }
  }

  private def isSupportedFileFormat(format: FileFormat): Boolean = {
    format match {
      case d: DataSourceRegister if isSupportedFileFormatName(d.shortName) => true
      case _ => false
    }
  }

  private def isSupportedFileFormatName(name: String): Boolean = {
    val supportedFileFormatsOverride = spark.sessionState.conf
      .getConfString(
        "spark.hyperspace.index.sources.defaultFileBasedSource.supportedFileFormats",
        "")
    if (supportedFileFormatsOverride.nonEmpty) {
      supportedFileFormatsOverride
        .toLowerCase(Locale.ROOT)
        .split(",")
        .map(_.trim)
        .contains(name.toLowerCase(Locale.ROOT))
    } else {
      supportedFormats.contains(name.toLowerCase(Locale.ROOT))
    }
  }

  /**
   * Reconstructs [[DataFrame]] using the given [[Relation]].
   *
   * @param relation [[Relation]] object to reconstruct [[DataFrame]] with.
   * @return [[DataFrame]] object if the given 'relation' can be processed by this provider.
   *         Otherwise, None.
   */
  override def reconstructDataFrame(relation: Relation): Option[DataFrame] = {
    if (isSupportedFileFormatName(relation.fileFormat)) {
      val dataSchema = DataType.fromJson(relation.dataSchemaJson).asInstanceOf[StructType]
      val df = spark.read
        .schema(dataSchema)
        .format(relation.fileFormat)
        .options(relation.options)
        .load(relation.rootPaths: _*)
      Some(df)
    } else {
      None
    }
  }

  /**
   * Computes the signature using the given [[LogicalRelation]].
   *
   * @param logicalRelation logical relation to compute signature from.
   * @return Signature computed if the given 'logicalRelation' can be processed by this provider.
   *         Otherwise, None.
   */
  override def signature(logicalRelation: LogicalRelation): Option[String] = {
    // Currently we are only collecting plan fingerprint from hdfs file based scan nodes.
    logicalRelation.relation match {
      case HadoopFsRelation(location: PartitioningAwareFileIndex, _, _, _, format, _)
          if isSupportedFileFormat(format) =>
        val result = location.allFiles.foldLeft("") { (acc: String, f: FileStatus) =>
          HashingUtils.md5Hex(acc + fingerprint(f))
        }
        Some(result)
      case _ => None
    }
  }

  /**
   * Fingerprints a file.
   *
   * @param fileStatus file status.
   * @return the fingerprint of a file.
   */
  private def fingerprint(fileStatus: FileStatus): String = {
    fileStatus.getLen.toString + fileStatus.getModificationTime.toString +
      fileStatus.getPath.toString
  }
}
