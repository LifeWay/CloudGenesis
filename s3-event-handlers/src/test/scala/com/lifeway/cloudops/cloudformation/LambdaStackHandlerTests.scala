package com.lifeway.cloudops.cloudformation

import com.amazonaws.services.lambda.runtime.events.SNSEvent
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNSRecord
import org.scalactic._
import io.circe.syntax._
import utest._

import scala.collection.JavaConverters._

object LambdaStackHandlerTests extends TestSuite {
  val tests = Tests {
    'lambdaHandler - {
      val errorMessageSender: String => Unit = s => throw new Exception(s)

      'returnUnitIfSuccessful - {
        val eventProcessor: EventProcessor = new EventProcessor {
          override val processEvent: S3File => Unit Or AutomationError = _ => Good(())
        }

        LambdaStackHandler.lambdaHandler(Good(eventProcessor), (_, _) => Good(()), errorMessageSender)(new SNSEvent)
        assert(true)
      }

      'sendMessageIfEventProcessorBuildingFails - {
        val e = intercept[Exception] {
          LambdaStackHandler.lambdaHandler(Bad(LambdaConfigError("missing lambda config")),
                                           (_, _) => Good(()),
                                           errorMessageSender)(new SNSEvent)
        }
        assert(e.getMessage.contains("missing lambda config"))
      }

      'sendMessageIfEventHandlerFails - {
        val eventProcessor: EventProcessor = new EventProcessor {
          override val processEvent: S3File => Unit Or AutomationError = _ => Good(())
        }

        val e = intercept[Exception] {
          LambdaStackHandler.lambdaHandler(Good(eventProcessor),
                                           (_, _) => Bad(StackError("the first error")),
                                           errorMessageSender)(new SNSEvent)
        }
        assert(e.getMessage.contains("the first error"))
      }
    }

    'loadEventProcessor - {
      'successfullyLoadEventProcessor - {
        var envFetch: (String) => Option[String] = {
          case "IAM_ASSUME_ROLE_NAME"    => Some("IAM_ROLE_NAME")
          case "CF_EVENTS_TOPIC_NAME"    => Some("SNS_ARN_FOR_CF_EVENTS")
          case "CLOUDGENESIS_ACCOUNT_ID" => Some("123456789")
          case _                         => throw new IllegalArgumentException
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
          case "IAM_ASSUME_ROLE_NAME"    => None
          case "CF_EVENTS_TOPIC_NAME"    => Some("SNS_ARN_FOR_CF_EVENTS")
          case "CLOUDGENESIS_ACCOUNT_ID" => Some("123456789")
          case _                         => throw new IllegalArgumentException
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

        val expected = Bad(
          LambdaConfigError(
            "IAM_ASSUME_ROLE_NAME, CF_EVENTS_TOPIC_ARN, CLOUDGENESIS_ACCOUNT_ID env variables were not set."))
        assert(processor == expected)
      }
    }

    'eventHandler - {
      'returnUnitForSuccessful - {
        val eventProcessor = new EventProcessor {
          override val processEvent: S3File => Unit Or AutomationError = _ => Good(())
        }
        val testEvents = S3File("bucket-name", "stacks/1234/some/file.yaml", "version-xyz", CreateUpdateEvent)
        val snsEvent   = buildSNSEventForTesting(testEvents)

        val result = LambdaStackHandler.eventHandler(eventProcessor, snsEvent)

        assert(result.isGood)
      }

      'returnErrorIfMoreThanOneRecordPerSNSEvent - {
        val eventProcessor = new EventProcessor {
          override val processEvent: S3File => Unit Or AutomationError = _ => Good(())
        }

        val snsRecord1 = new SNSRecord().withSns(new SNSEvent.SNS().withMessage("dummy-rec"))
        val snsRecord2 = new SNSRecord().withSns(new SNSEvent.SNS().withMessage("dummy-rec"))
        val snsEvent   = new SNSEvent().withRecords(Seq(snsRecord1, snsRecord2).asJava)

        val result = LambdaStackHandler.eventHandler(eventProcessor, snsEvent)

        assert(result.isBad)
        assert(
          result.swap.get == StackError(
            "Stack Handler can only receive records that have gone thru the Demuxer that emits a single event"))
      }

      'returnErrorIfSNSJsonDoesntParseAsS3File - {
        val eventProcessor = new EventProcessor {
          override val processEvent: S3File => Unit Or AutomationError = _ => Good(())
        }

        val snsEventMsg = new SNSEvent.SNS().withMessage("""not valid json""".stripMargin)
        val snsRecord   = new SNSRecord().withSns(snsEventMsg)
        val snsEvent    = new SNSEvent().withRecords(Seq(snsRecord).asJava)

        val result = LambdaStackHandler.eventHandler(eventProcessor, snsEvent)

        assert(result.isBad)
        assert(
          result.swap.get
            .asInstanceOf[StackError]
            .msg
            .contains("Unable to parse SNS Message into S3File type. Reason:"))
      }

      'returnErrorFromProcessedEvent - {
        val eventProcessor = new EventProcessor {
          override val processEvent: S3File => Unit Or AutomationError = f => Bad(StackError("boom"))
        }

        val testEvents = S3File("bucket-name", "stacks/1234/some/file.yaml", "version-xyz", CreateUpdateEvent)
        val snsEvent   = buildSNSEventForTesting(testEvents)
        val result     = LambdaStackHandler.eventHandler(eventProcessor, snsEvent)

        assert(result.isBad)
        assert(result.swap.get == StackError("boom"))
      }

    }
  }

  def buildSNSEventForTesting(s3file: S3File): SNSEvent = {
    val snsEventMsg = new SNSEvent.SNS().withMessage(s3file.asJson.noSpaces)
    val snsRecord   = new SNSRecord().withSns(snsEventMsg)
    new SNSEvent().withRecords(Seq(snsRecord).asJava)
  }
}
