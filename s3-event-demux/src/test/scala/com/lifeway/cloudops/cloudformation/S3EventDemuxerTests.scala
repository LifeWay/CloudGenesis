package com.lifeway.cloudops.cloudformation

import com.amazonaws.services.lambda.runtime.events.SNSEvent
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNSRecord
import com.amazonaws.services.s3.event.S3EventNotification
import com.amazonaws.services.s3.event.S3EventNotification.{
  S3BucketEntity,
  S3Entity,
  S3EventNotificationRecord,
  S3ObjectEntity
}
import com.amazonaws.services.sns.model.PublishResult
import utest._

import scala.collection.JavaConverters._

object S3EventDemuxerTests extends TestSuite {
  val tests = Tests {
    'loadHandler - {
      'returnEventHandlerFunctionSuccessfullyWhenVarsAreAvailable - {
        val varFetch: String => Option[String] = {
          case "S3FILE_EVENTS_TOPIC_ARN" => Some("S3FileEventsArn")
          case "SNS_ERROR_TOPIC_ARN"     => Some("SNSErrorArn")
          case _                         => None
        }
        S3EventDemuxer.loadHandler((_, _) => (_, _) => ())(varFetch, null)
        //If we get here, test passes as no exception was thrown.
        assert(true)
      }

      'raiseExceptionIfMissingEnvVars - {
        val varFetch: String => Option[String] = {
          case "S3FILE_EVENTS_TOPIC_ARN" => Some("S3FileEventsArn")
          case "SNS_ERROR_TOPIC_ARN"     => None
          case _                         => None
        }
        val exception = intercept[Exception] {
          S3EventDemuxer.loadHandler((_, _) => (_, _) => ())(varFetch, null)
        }

        assert(exception.getMessage == "S3FILE_EVENTS_TOPIC_ARN or SNS_ERROR_TOPIC_ARN are missing from env vars")
      }
    }

    'snsS3EventDemuxer - {
      'successfullyDemuxEvents - {

        val snsEvent =
          buildSNSEventForTesting(Seq(("bucket", "file1.yaml", "version1A"), ("bucket", "file2.yaml", "version2A")))

        val sendDemuxEvent: S3File => PublishResult = {
          case S3File("bucket", "file1.yaml", "version1A", CreateUpdateEvent) => new PublishResult
          case S3File("bucket", "file2.yaml", "version2A", CreateUpdateEvent) => new PublishResult
          case _                                                              => throw new IllegalArgumentException("Unexpected value!!")
        }

        S3EventDemuxer.snsS3EventDemuxer(_ => throw new IllegalArgumentException("should not be called"),
                                         sendDemuxEvent)(snsEvent, CreateUpdateEvent)
        //If we get here, test passes as no exception was thrown.
        assert(true)
      }

      'sendErrorMessagesViaFunction - {
        val snsEvent: SNSEvent = new SNSEvent()
          .withRecords(Seq(new SNSRecord().withSns(new SNSEvent.SNS().withMessage("not-a-s3-event-record"))).asJava)

        val sendDemuxErroprEvent: String => PublishResult = s => throw new Exception(s)

        val ex = intercept[Exception] {
          S3EventDemuxer.snsS3EventDemuxer(
            sendDemuxErroprEvent,
            _ => throw new IllegalArgumentException("should not be called"))(snsEvent, CreateUpdateEvent)
        }
        assert(ex.getMessage.contains(
          "ParsingError: Failed to turn SNSMessage into S3Event. This shouldn't occur unless non S3 event data was sent to topic."))
      }
    }
  }

  def buildSNSEventForTesting(changedFiles: Seq[(String, String, String)]): SNSEvent = {
    val s3EventNotificationRecords: Seq[S3EventNotificationRecord] = changedFiles.map { file =>
      val (bucketName, fileName, versionId) = file

      val s3Entity = new S3Entity(null,
                                  new S3BucketEntity(bucketName, null, null),
                                  new S3ObjectEntity(fileName, null, null, versionId, null),
                                  null)
      new S3EventNotificationRecord(null, null, null, null, null, null, null, s3Entity, null)
    }

    val snsEventNotificationRecord = new S3EventNotification(s3EventNotificationRecords.asJava)

    val snsEventMsg = new SNSEvent.SNS().withMessage(snsEventNotificationRecord.toJson)
    val snsRecord   = new SNSRecord().withSns(snsEventMsg)
    new SNSEvent().withRecords(Seq(snsRecord).asJava)
  }
}
