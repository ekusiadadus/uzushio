package com.worksap.nlp.uzushio.lib.runners

import com.worksap.nlp.uzushio.lib.warc.{WarcEntryParser, WarcLoader}
import org.apache.spark.sql.{SaveMode, SparkSession}
import org.rogach.scallop.ScallopConf
import org.slf4j.LoggerFactory

object ExtractParagraphsFromWARC {
  private val logger = LoggerFactory.getLogger("WarcTextExtraction")

  case class Args(
      input: Seq[String],
      output: String,
      languages: Set[String],
      maxPartitions: Int = 10000,
      compression: String = "zstd"
  )

  def run(args: Args)(spark: SparkSession): Unit = {
    import spark.implicits._
    logger.info(
      s"filtering out documents in following languages: ${args.languages.mkString(",")}"
    )

    val warcData =
      WarcLoader.readWarcFiles(spark.sparkContext, args.input.mkString(","))
    val crawlResponses = warcData.filter(x => x.isResponse && !x.isTruncated)

    val inputDocuments = spark.sparkContext.longAccumulator("inputDocs")
    val convertedDocs = spark.sparkContext.longAccumulator("convertedDocs")

    val items = crawlResponses.mapPartitions(
      { iter =>
        val converter = new WarcEntryParser()
        iter.flatMap { item =>
          inputDocuments.add(1)
          converter.convert(item).map { x =>
            convertedDocs.add(1)
            x
          }
        }
      },
      preservesPartitioning = true
    )

    val filtered =
      if (args.languages.isEmpty) items
      else items.filter(doc => args.languages.contains(doc.language))

    val frame = filtered.coalesce(args.maxPartitions).toDF()
    frame.write
      .mode(SaveMode.Overwrite)
      .partitionBy("language")
      .option("compression", args.compression)
      .parquet(args.output)
    logger.info(
      s"input docs=${inputDocuments.value}, processed=${convertedDocs.value}"
    )

  }
}

/**
 * This is entry point for WARC text extraction run as a simple application, e.g. from IDE
 */
object WarcTextExtractionRaw {

  // noinspection TypeAnnotation
  class ConfigParser(args: Seq[String]) extends ScallopConf(args) {
    val input = opt[List[String]](required = true)
    val output = opt[String](required = true)
    val language = opt[List[String]](default = Some(Nil))
    val maxPartitions = opt[Int](default = Some(2000))
    val compression = opt[String](default = Some("zstd"))
    verify()

    def asArgs(): ExtractParagraphsFromWARC.Args = ExtractParagraphsFromWARC.Args(
      input = input.apply(),
      output = output.apply(),
      languages = language.apply().flatMap(_.split(',')).toSet,
      maxPartitions = maxPartitions(),
      compression = compression()
    )
  }

  def main(args: Array[String]): Unit = {
    import com.worksap.nlp.uzushio.lib.utils.Resources._

    val cfg = new ConfigParser(args)
    SparkSession
      .builder()
      .master("local")
      .appName("WarcTextExtractor")
      .getOrCreate()
      .use { sc =>
        ExtractParagraphsFromWARC.run(cfg.asArgs())(sc)
      }

  }
}
