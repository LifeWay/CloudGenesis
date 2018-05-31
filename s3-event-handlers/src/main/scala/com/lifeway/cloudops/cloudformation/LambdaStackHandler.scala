package com.lifeway.cloudops.cloudformation

import akka.actor.ActorSystem
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SNSEvent
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.securitytoken.{AWSSecurityTokenService, AWSSecurityTokenServiceClientBuilder}
import com.amazonaws.services.sns.{AmazonSNS, AmazonSNSClientBuilder}
import org.scalactic.{Or, _}
import com.lifeway.cloudops.cloudformation.Types._
import org.slf4j.LoggerFactory
import io.circe.parser._

import scala.collection.JavaConverters._

/**
  * Handles the Lambda Invokes from SNS Events coming from the S3 Event Demuxer.
  *
  */
// $COVERAGE-OFF$
class LambdaStackHandler {

  val stsClient: AWSSecurityTokenService              = AWSSecurityTokenServiceClientBuilder.defaultClient()
  val s3Client: AmazonS3                              = AmazonS3ClientBuilder.defaultClient()
  val snsClient: AmazonSNS                            = AmazonSNSClientBuilder.defaultClient()
  val iamCapabilities: IAMCapabilityEnabled           = sys.env.get("IAM_CAPABILITIES_ENABLED").exists(_.toBoolean)
  val semanticStackNaming: SemanticStackNamingEnabled = sys.env.get("SEMANTIC_STACK_NAMING").forall(_.toBoolean)
  val snsExternalNotifyTopicArn: ExternalNotifySNSArn = sys.env.get("SNS_EXTERNAL_TOPIC_NOTIFY_ARN")
  val cfServiceRoleName: CFServiceRoleName            = sys.env.get("IAM_CF_SERVICE_ROLE_NAME")
  val changeSetNamePrefix: ChangeSetNamePrefix        = sys.env.get("CF_CHANGE_SET_NAME_PREFIX")
  val trackingTagName: TrackingTagName                = sys.env.getOrElse("TRACKING_TAG_NAME", "GitFormation:stack-file")
  val trackingTagValuePrefix: TrackingTagValuePrefix  = sys.env.get("TRACKING_TAG_PREFIX")
  val system                                          = ActorSystem("SchedulerSystem")
  val snsErrorArn: SNSErrorArn = sys.env
    .getOrElse("SNS_ERROR_TOPIC_ARN", throw new Exception("SNS_ERROR_TOPIC_ARN is not set. All processing has failed."))

  val eventProcessorOr: EventProcessor Or AutomationError =
    LambdaStackHandler.loadEventProcessor(varName =>
      sys.env.get(varName).flatMap(x => if (x.isEmpty) None else Some(x)))(
      stsClient,
      s3Client,
      snsClient,
      semanticStackNaming,
      system,
      iamCapabilities,
      cfServiceRoleName,
      changeSetNamePrefix,
      trackingTagName,
      trackingTagValuePrefix,
      snsExternalNotifyTopicArn
    )
  val handler = LambdaStackHandler.lambdaHandler(
    eventProcessorOr,
    LambdaStackHandler.eventHandler,
    (input: String) => snsClient.publish(snsErrorArn, input, "GitFormation: Error")) _

  /**
    * Given an SNS Event that should be wrapping an S3File type (coming from demuxer), process it.
    *
    * @param event
    * @param ctx
    */
  def handler(event: SNSEvent, ctx: Context): Unit = handler(event)
}

// $COVERAGE-ON$

object LambdaStackHandler {
  val logger = LoggerFactory.getLogger("com.lifeway.cloudops.cloudformation.LambdaStackHandler")

  /**
    * Handles an SNSEvent based on the EventType and returns a response for AWS Lambda' Async invokes (Needs to return
    * Units (Java Voids) and throw exceptions for the purposes of AWS JVM Lambda)
    *
    * ~~~WARNING~~~
    * Careful about throwing any exceptions. If the Exception was an AWS 500 level error, then it can be thrown as DLQ.
    * However, there is still risk here, assume that a stack got updated twice within a minute (e.g. developer realized
    * stack commit was wrong and quickly fired another commit that got merged). If the first one fails because AWS was
    * having a hick-up, and retries after a DLQ timeout, but the second commit went thru fine, only to have the first
    * overwrite the second after a DLQ delay.
    *
    * As a result, for the time being we are not retrying even 500 level AWS errors. The SDK is already going to do this
    * anyways, so there is already this risk - we just don't want to delay it even further by putting many seconds in
    * between retries like the DLQ currently does.
    *
    * Therefore, it is recommended to capture all exceptions and push them down your own failure handler outside of the
    * capabilities of DLQ processing.
    *
    * Long-term it would be ideal to turn the S3 Notifications from the Demuxer into a kinesis stream such that the next
    * event is not executed until the preceding one succeeds. This would naturally keep things in order assuming
    * the Demuxer is able to execute the events in as close to as possible ordering from what SNS events from S3 is
    * giving us.
    *
    * @param eventProcessorOr
    * @param eventHandlerFun
    * @param event
    */
  def lambdaHandler(eventProcessorOr: EventProcessor Or AutomationError,
                    eventHandlerFun: (EventProcessor, SNSEvent) => Unit Or AutomationError,
                    snsErrorMsg: String => Unit)(event: SNSEvent): Unit =
    (for {
      eventProcessor <- eventProcessorOr
      eventHandled   <- eventHandlerFun(eventProcessor, event)
    } yield eventHandled).fold(
      good => good,
      bad => snsErrorMsg(bad.toString)
    )

  /**
    * Loads the EventProcessor that is used for processing the events. This should be loaded on function cold start and
    * kept in memory outside of the handler invoke.
    *
    * @param envFetch
    * @param stsClient
    * @param s3Client
    * @param snsClient
    * @param semanticStackNaming
    * @param system
    * @param iamCapabilities
    * @param externalSNSArn
    * @return
    */
  def loadEventProcessor(envFetch: String => Option[String])(
      stsClient: AWSSecurityTokenService,
      s3Client: AmazonS3,
      snsClient: AmazonSNS,
      semanticStackNaming: SemanticStackNamingEnabled,
      system: ActorSystem,
      iamCapabilities: IAMCapabilityEnabled,
      cFServiceRoleName: CFServiceRoleName,
      changeSetNamePrefix: ChangeSetNamePrefix,
      trackingTagName: TrackingTagName,
      trackingTagValuePrefix: TrackingTagValuePrefix,
      externalSNSArn: ExternalNotifySNSArn
  ): EventProcessor Or AutomationError = {
    val eventProcessorOpt: Option[EventProcessor] = for {
      assumeRoleName     <- envFetch("IAM_ASSUME_ROLE_NAME")
      snsEventsTopicName <- envFetch("CF_EVENTS_TOPIC_NAME")
      accountId          <- envFetch("GITFORMATION_ACCOUNT_ID")
    } yield {
      val executors: Map[EventType, StackExecutor] = Map(
        CreateUpdateEvent -> new CreateUpdateStackExecutorDefaultFunctions(system,
                                                                           iamCapabilities,
                                                                           cFServiceRoleName,
                                                                           changeSetNamePrefix,
                                                                           trackingTagName,
                                                                           trackingTagValuePrefix,
                                                                           accountId,
                                                                           snsEventsTopicName),
        DeletedEvent -> DeleteStackExecutorDefaultFunctions
      )
      new EventProcessorDefaultFunctions(stsClient,
                                         s3Client,
                                         snsClient,
                                         externalSNSArn,
                                         semanticStackNaming,
                                         executors,
                                         assumeRoleName)
    }
    Or.from(eventProcessorOpt,
            "IAM_ASSUME_ROLE_NAME, CF_EVENTS_TOPIC_ARN, GITFORMATION_ACCOUNT_ID env variables were not set.")
      .badMap(e => LambdaConfigError(e))
  }

  /**
    * Responsible for taking the SNSEvent and the EventType (CreateUpdate or Delete) and calling the provided
    * eventProcessor function to process the S3Event.
    *
    * A single SNSEvent coming into this lambda should contain a single S3EventNotificationRecord that has already
    * been demuxed from the original SNS S3 Notifcation topic. This function gaurds against this fact and WILL return
    * errors and cease processing if the event wasn't properly demuxed and parsable into an S3File.
    *
    * @param eventProcessor
    * @param event
    * @return
    */
  def eventHandler(eventProcessor: EventProcessor, event: SNSEvent): Unit Or AutomationError = {
    val records = event.getRecords.asScala
    if (records.size != 1)
      Bad(
        StackError("Stack Handler can only receive records that have gone thru the Demuxer that emits a single event"))
    else {
      val parsed = Or
        .from(for {
          json   <- parse(records.head.getSNS.getMessage)
          s3File <- json.as[S3File]
        } yield s3File)
        .badMap(x => StackError(s"Unable to parse SNS Message into S3File type. Reason: ${x.getMessage}"))

      parsed.flatMap(s3File => eventProcessor.processEvent(s3File))
    }
  }
}
