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
import org.scalactic._
import org.scalactic.Accumulation._
import utest._

import scala.collection.JavaConverters._

object LambdaStackHandlerTests extends TestSuite {
  val tests = Tests {
    'lambdaHandler - {
      'returnUnitIfSuccessful - {
        val eventProcessor: EventProcessor = new EventProcessor {
          override val processEvent: (S3EventNotification, EventType) => Or[Unit, Every[AutomationError]] =
            (_, _) => Good(())
        }

        LambdaStackHandler.lambdaHandler(Good(eventProcessor), (_, _, _) => Good(()))(CreateUpdateEvent, new SNSEvent)
        assert(true)
      }

      'raiseExceptionIfEventProcessorBuildingFails - {
        val e = intercept[Exception] {
          LambdaStackHandler.lambdaHandler(Bad(One(LambdaConfigError("missing lambda config"))), (_, _, _) => Good(()))(
            CreateUpdateEvent,
            new SNSEvent)
        }
        assert(e.getMessage.contains("missing lambda config"))
      }

      'raiseExceptionIfEventHandlerFails - {
        val eventProcessor: EventProcessor = new EventProcessor {
          override val processEvent: (S3EventNotification, EventType) => Or[Unit, Every[AutomationError]] =
            (_, _) => Good(())
        }
        val e = intercept[Exception] {
          LambdaStackHandler.lambdaHandler(
            Good(eventProcessor),
            (_, _, _) => Bad(Every(StackError("the first error"), StackError("the second error"))))(CreateUpdateEvent,
                                                                                                    new SNSEvent)
        }
        assert(e.getMessage.contains("the first error"))
        assert(e.getMessage.contains("the second error"))
      }
    }

    'loadEventProcessor - {
      'successfullyLoadEventProcessor - {
        var envFetch: (String) => Option[String] = {
          case "IAM_ASSUME_ROLE_NAME" => Some("IAM_ROLE_NAME")
          case "CF_EVENTS_TOPIC_ARN"  => Some("SNS_ARN_FOR_CF_EVENTS")
          case _                      => throw new IllegalArgumentException
        }

        val processor = LambdaStackHandler.loadEventProcessor(envFetch)(null,
                                                                        null,
                                                                        null,
                                                                        semanticStackNaming = true,
                                                                        null,
                                                                        iamCapabilities = true,
                                                                        null,
                                                                        null,
                                                                        null,
                                                                        null,
                                                                        null)
        assert(processor.isGood)
      }

      'returnErrorIfMissingOneOfRoleVars - {
        var envFetch: (String) => Option[String] = {
          case "IAM_ASSUME_ROLE_NAME" => None
          case "CF_EVENTS_TOPIC_ARN"  => Some("SNS_ARN_FOR_CF_EVENTS")
          case _                      => throw new IllegalArgumentException
        }

        val processor = LambdaStackHandler.loadEventProcessor(envFetch)(null,
                                                                        null,
                                                                        null,
                                                                        semanticStackNaming = true,
                                                                        null,
                                                                        iamCapabilities = true,
                                                                        null,
                                                                        null,
                                                                        null,
                                                                        null,
                                                                        null)

        val expected =
          Bad(One(LambdaConfigError("IAM_ASSUME_ROLE_NAME or CF_EVENTS_TOPIC_ARN env variables were not set.")))
        assert(processor == expected)
      }
    }

    'eventHandler - {
      'returnUnitForSuccessful - {
        val eventProcessor = new EventProcessor {
          override val processEvent: (S3EventNotification, EventType) => Or[Unit, Every[AutomationError]] =
            (_, _) => Good(())
        }

        val testEvents = Seq(
          ("bucket-name", "stacks/1234/some/file.yaml", "version-xyz"),
          ("bucket-name", "stacks/789/something/else.yaml", "version-123"),
          ("bucket-name", "stacks/accountName.341/something/else.yaml", "version-738")
        )

        val snsEvent = buildSNSEventForTesting(testEvents)
        val result   = LambdaStackHandler.eventHandler(eventProcessor, CreateUpdateEvent, snsEvent)

        assert(result.isGood)
      }

      'returnErrorIfSNSJsonDoesntParse - {
        val eventProcessor = new EventProcessor {
          override val processEvent: (S3EventNotification, EventType) => Or[Unit, Every[AutomationError]] =
            (_, _) => Good(())
        }

        val snsEventMsg = new SNSEvent.SNS().withMessage("""
            |not valid json
          """.stripMargin)
        val snsRecord   = new SNSRecord().withSns(snsEventMsg)
        val snsEvent    = new SNSEvent().withRecords(Seq(snsRecord).asJava)

        val result = LambdaStackHandler.eventHandler(eventProcessor, CreateUpdateEvent, snsEvent)

        assert(result.isBad)
        assert(result.swap.get == One(StackError("Unable to parse Json String.")))
      }

      'returnErrorsFromProcessedEvent - {
        val eventProcessor = new EventProcessor {
          override val processEvent: (S3EventNotification, EventType) => Or[Unit, Every[AutomationError]] =
            (r, _) => {
              r.getRecords.asScala.toSeq
                .map { s3 =>
                  s3.getS3.getObject.getKey match {
                    case "stacks/1234/some/file.yaml"                 => Bad(One(StackError("boom")))
                    case "stacks/789/something/else.yaml"             => Good(())
                    case "stacks/accountName.341/something/else.yaml" => Bad(One(StackError("else boom")))
                  }
                }
                .combined
                .map(_ => ())
            }
        }

        val testEvents = Seq(
          ("bucket-name", "stacks/1234/some/file.yaml", "version-xyz"),
          ("bucket-name", "stacks/789/something/else.yaml", "version-123"),
          ("bucket-name", "stacks/accountName.341/something/else.yaml", "version-738")
        )

        val snsEvent = buildSNSEventForTesting(testEvents)
        val result   = LambdaStackHandler.eventHandler(eventProcessor, CreateUpdateEvent, snsEvent)

        assert(result.isBad)
        assert(result.swap.get == Many(StackError("boom"), StackError("else boom")))
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
