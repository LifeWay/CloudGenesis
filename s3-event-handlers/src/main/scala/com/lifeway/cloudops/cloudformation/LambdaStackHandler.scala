package com.lifeway.cloudops.cloudformation

import akka.actor.ActorSystem
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SNSEvent
import com.amazonaws.services.s3.event.S3EventNotification
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.securitytoken.{AWSSecurityTokenService, AWSSecurityTokenServiceClientBuilder}
import com.amazonaws.services.sns.{AmazonSNS, AmazonSNSClientBuilder}
import org.scalactic.{Or, _}
import org.scalactic.Accumulation._
import com.lifeway.cloudops.cloudformation.Types._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * Handles the Lambda Invokes from SNS Events.
  * Two Handlers defined - one for CreateUpdate and one for Delete. Therefore, this same package will be distributed
  * twice for two different lambdas, each with a different handler.
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
  val system                                          = ActorSystem("SchedulerSystem")
  val eventProcessorOr: EventProcessor Or Every[AutomationError] =
    LambdaStackHandler.loadEventProcessor(varName =>
      sys.env.get(varName).flatMap(x => if (x.isEmpty) None else Some(x)))(stsClient,
                                                                           s3Client,
                                                                           snsClient,
                                                                           semanticStackNaming,
                                                                           system,
                                                                           iamCapabilities,
                                                                           cfServiceRoleName,
                                                                           changeSetNamePrefix,
                                                                           snsExternalNotifyTopicArn)
  val handler = LambdaStackHandler.lambdaHandler(eventProcessorOr, LambdaStackHandler.eventHandler) _

  /**
    * Given an SNS Event that should be wrapping an S3 message, process it and then transform the type system back
    * to Lambda void or basic Exception with a String of error details so it shows up in logs / passed down the DLQ
    * chain
    *
    * @param event
    * @param ctx
    */
  def createUpdateHandler(event: SNSEvent, ctx: Context): Unit = handler(CreateUpdateEvent, event)

  /**
    * Given an SNS Event that should be wrapping an S3 message, process it and then transform the type system back
    * to Lambda void or basic Exception with a String of error details so it shows up in logs / passed down the DLQ
    * chain
    *
    * @param event
    * @param ctx
    */
  def deleteHandler(event: SNSEvent, ctx: Context): Unit = handler(DeletedEvent, event)
}
// $COVERAGE-ON$

object LambdaStackHandler {
  val logger = LoggerFactory.getLogger("com.lifeway.cloudops.cloudformation.LambdaStackHandler")

  /**
    * Handles an SNSEvent based on the EventType and returns a response for AWS Lambda' Async invokes. Needs to return
    * Units (Java Voids) and throw exceptions for the purposes of AWS JVM Lambda.
    *
    * @param eventProcessorOr
    * @param eventHandlerFun
    * @param eventType
    * @param event
    */
  def lambdaHandler(eventProcessorOr: EventProcessor Or Every[AutomationError],
                    eventHandlerFun: (EventProcessor, EventType, SNSEvent) => Unit Or Every[AutomationError])(
      eventType: EventType,
      event: SNSEvent): Unit =
    (for {
      eventProcessor <- eventProcessorOr
      eventHandled   <- eventHandlerFun(eventProcessor, eventType, event)
    } yield eventHandled).fold(
      good => {
        println("it was good!")
        good
      },
      everyBad => throw new Exception(everyBad.mkString("/n"))
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
      externalSNSArn: ExternalNotifySNSArn): EventProcessor Or Every[AutomationError] = {
    val eventProcessorOpt: Option[EventProcessor] = for {
      assumeRoleName <- envFetch("IAM_ASSUME_ROLE_NAME")
      snsEventsArn   <- envFetch("CF_EVENTS_TOPIC_ARN")
    } yield {
      val executors: Map[EventType, StackExecutor] = Map(
        CreateUpdateEvent -> new CreateUpdateStackExecutorDefaultFunctions(system,
                                                                           iamCapabilities,
                                                                           cFServiceRoleName,
                                                                           changeSetNamePrefix,
                                                                           snsEventsArn),
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

    Or.from(eventProcessorOpt, "IAM_ASSUME_ROLE_NAME or CF_EVENTS_TOPIC_ARN env variables were not set.")
      .badMap(e => One(LambdaConfigError(e)))
  }

  /**
    * Responsible for taking the SNSEvent, processing some ENV vars, and the EventType (CreateUpdate or Delete) and
    * calling the provided eventProcessor function to process the S3Event.
    *
    * The primary role of this function is to validate the config and extract from the SNSEvent message the underlying
    * S3Event objects
    *
    * @param eventProcessor
    * @param eventType
    * @param event
    * @return
    */
  def eventHandler(eventProcessor: EventProcessor,
                   eventType: EventType,
                   event: SNSEvent): Unit Or Every[AutomationError] = {

    /**
      * A single SNSEvent may contain multiple S3Event messages which each can also contain multiple files that were
      * touched. We need to treat each unit as an individual failure, being careful not to fail everything just because
      * one part failed. As that would be dangerous in terms of keeping the git repo <--> CloudFormation in-sync with
      * one another. Each stack must have an equal chance to get executed if they share the same event!
      */
    val processedResults: Seq[Unit Or Every[AutomationError] Or One[AutomationError]] = event.getRecords.asScala.map {
      sns =>
        val s3Event: S3EventNotification Or One[StackError] =
          Or.from(Try(S3EventNotification.parseJson(sns.getSNS.getMessage)))
            .badMap(x => One(StackError(x.getMessage)))
        val processed: Or[Or[Unit, Every[AutomationError]], One[AutomationError]] =
          s3Event.map(event => eventProcessor.processEvent(event, eventType))
        processed
    }.toSeq

    //Combine the results into a single of success of Unit (for JVM Async Lambda) or a summation of all failures
    processedResults.combined.flatMap(_.combined.map(_ => ()))
  }
}
