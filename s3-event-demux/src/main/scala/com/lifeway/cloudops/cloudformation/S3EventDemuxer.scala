package com.lifeway.cloudops.cloudformation

import com.amazonaws.services.lambda.runtime.events.SNSEvent
import com.amazonaws.services.s3.event.S3EventNotification
import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord
import com.amazonaws.services.sns.model.PublishResult
import com.amazonaws.services.sns.{AmazonSNS, AmazonSNSClientBuilder}
import org.scalactic.{One, Or}
import org.slf4j.LoggerFactory
import io.circe.syntax._

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * A single SNSEvent may contain multiple S3Event messages which each can also contain multiple files that were
  * touched. We need to treat each unit as an individual unit, creating a new SNS message for each one.
  *
  * NOTE: ordering is not guaranteed! If the same file was touched more than once in the same time period. S3 may put
  * those into the same SNSEvent / S3Event. There is some things you can attempt to do with the S3Event Sequencer, but
  * this is a very basic demuxer. If you care strongly about ordering, then a better demuxer would properly order the
  * events and then put the messages onto an ordered queue (e.g. SQS or Kinesis) instead of back onto another SNS topic
  * since SNS doesn't have any garantees around ordering.
  *
  * ~~~ WARNING ~~~
  * Every attempt should be made at this function not throwing errors and operating as fast as possible. If this
  * function dies or throws an error, the entire event will be replayed as a result of the DLQ. If there were multiple
  * events within the message where some of them were previously processed, they will be processed again.
  */
class S3EventDemuxer {

  val snsClient: AmazonSNS = AmazonSNSClientBuilder.defaultClient()
  var envFetch: String => Option[String] = varName =>
    sys.env.get(varName).flatMap(x => if (x.isEmpty) None else Some(x))
  val demuxHandler: (SNSEvent, EventType) => Unit = S3EventDemuxer.loadHandler()(envFetch, snsClient)

  def createUpdateHandler(event: SNSEvent): Unit = demuxHandler(event, CreateUpdateEvent)

  def deleteHandler(event: SNSEvent): Unit = demuxHandler(event, DeletedEvent)
}

object S3EventDemuxer {
  val logger = LoggerFactory.getLogger("com.lifeway.cloudops.cloudformation.S3EventDemuxer")

  /**
    * Loads the handler that is used for processing all events. This is loaded on function cold start and kept in memory
    * outside of the handler invoke.
    *
    * @param eventHandler
    * @param envFetch
    * @param snsClient
    * @return
    */
  def loadHandler(eventHandler: (String => PublishResult, S3File => PublishResult) => (SNSEvent, EventType) => Unit =
                    S3EventDemuxer.snsS3EventDemuxer)(envFetch: String => Option[String],
                                                      snsClient: AmazonSNS): (SNSEvent, EventType) => Unit = {
    val snsArnsOpt = for {
      s3FileEventsArn  <- envFetch("S3FILE_EVENTS_TOPIC_ARN")
      snsErrorTopicArn <- envFetch("SNS_ERROR_TOPIC_ARN")
    } yield {
      val sendS3File: S3File => PublishResult = input =>
        snsClient.publish(s3FileEventsArn, input.asJson.noSpaces, "Demuxed S3File Event")
      val sendError: String => PublishResult = input => snsClient.publish(snsErrorTopicArn, input, "Demuxed Error")

      (sendS3File, sendError)
    }

    Or.from(snsArnsOpt, "S3FILE_EVENTS_TOPIC_ARN or SNS_ERROR_TOPIC_ARN are missing from env vars")
      .fold(
        gv => eventHandler(gv._2, gv._1),
        bf => throw new Exception(bf)
      )
  }

  /**
    * Handles the events coming off of the SNS topic from the S3 Event Notification hooks. Demux's the events down to
    * where there is just a single S3File event being pushed onto an SNS topic one message at a time. Any failures here
    * should only happen if there was bad data, and they are pushed onto a seperate SNS topic.
    *
    * @param sendErrorMsg
    * @param sendS3FileMsg
    * @param event
    * @param eventType
    */
  def snsS3EventDemuxer(sendErrorMsg: String => PublishResult,
                        sendS3FileMsg: S3File => PublishResult)(event: SNSEvent, eventType: EventType): Unit = {
    val snsMessages: Seq[String] = event.getRecords.asScala.map(_.getSNS.getMessage)
    val s3EventNotifications: Seq[Seq[S3File] Or One[DemuxError]] = snsMessages.map { msg =>
      Or.from {
          Try {
            val s3Notifications: Seq[S3EventNotificationRecord] =
              S3EventNotification.parseJson(msg).getRecords.asScala.toSeq
            s3Notifications.map(e =>
              S3File(e.getS3.getBucket.getName, e.getS3.getObject.getKey, e.getS3.getObject.getVersionId, eventType))
          }
        }
        .badMap { x =>
          logger.warn(
            s"Apparent bad data (not from S3) was sent to topic and failed to parse as a S3EventNotification. ${x.getMessage}. Record: $msg")
          One(ParsingError(
            s"Failed to turn SNSMessage into S3Event. This shouldn't occur unless non S3 event data was sent to topic. Reason: ${x.getMessage}. Record: $msg"))
        }
    }

    s3EventNotifications.foreach { item =>
      item.fold(
        gv => gv.foreach(sendS3FileMsg),
        bf => sendErrorMsg(bf.mkString)
      )
    }
  }
}
