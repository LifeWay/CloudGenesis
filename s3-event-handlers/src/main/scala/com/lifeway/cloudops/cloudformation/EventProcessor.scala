package com.lifeway.cloudops.cloudformation

import java.io.IOException

import com.amazonaws.SdkClientException
import com.amazonaws.auth.{AWSCredentialsProvider, STSAssumeRoleSessionCredentialsProvider}
import com.amazonaws.services.cloudformation.{AmazonCloudFormation, AmazonCloudFormationClientBuilder}
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.event.S3EventNotification
import com.amazonaws.services.s3.model.{GetObjectRequest, ListVersionsRequest}
import com.amazonaws.services.securitytoken.AWSSecurityTokenService
import com.amazonaws.services.sns.AmazonSNS
import com.amazonaws.util.IOUtils
import io.circe.syntax._
import io.circe.yaml.parser
import org.scalactic.{Or, _}
import org.scalactic.Accumulation._
import com.lifeway.cloudops.cloudformation.Types._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.Try

trait EventProcessor {
  val processEvent: (S3EventNotification, EventType) => Unit Or Every[AutomationError]
}

// $COVERAGE-OFF$
class EventProcessorDefaultFunctions(stsClient: AWSSecurityTokenService,
                                     s3Client: AmazonS3,
                                     snsClient: AmazonSNS,
                                     externalSNSArn: ExternalNotifySNSArn,
                                     semanticStackNaming: SemanticStackNamingEnabled,
                                     stackExecutors: Map[EventType, StackExecutor],
                                     assumeRoleName: AssumeRoleName)
    extends EventProcessor {
  override val processEvent: (S3EventNotification, EventType) => Unit Or Every[AutomationError] = {
    val s3Content    = EventProcessor.getS3ContentAsString(s3Client) _
    val handler      = EventProcessor.handleStack(s3Content, snsClient, externalSNSArn, semanticStackNaming) _
    val clientLoader = EventProcessor.clientLoader(assumeRoleName, stsClient) _
    EventProcessor.processEvent(handler, clientLoader, stackExecutors)
  }
}
// $COVERAGE-ON$

object EventProcessor {
  val logger = LoggerFactory.getLogger("com.lifeway.cloudops.cloudformation.EventProcessor")

  /**
    * Parses account numbers out of a semantically named string, e.g. "stacks/some-account-name.123456789/something/something.key" while also
    * supporting strings where there was no account name added, e.g. "123456789"
    */
  val accountNumberParser: String => String = key => key.split("/", 3)(1).split("""\.""").reverse.head

  /**
    * Processes a single S3 Event returning a Good[Unit] or a Bad[Every[AutomationError]. Reminder: a single S3 event
    * may itself contain multiple events.
    *
    * @param handler
    * @param loadClients
    * @param stackExecutors
    * @param event
    * @param eventType
    * @return
    */
  def processEvent(handler: (AmazonCloudFormation, S3File, StackExecutor) => Unit Or AutomationError,
                   loadClients: Seq[S3File] => Map[String, AmazonCloudFormation],
                   stackExecutors: Map[EventType, StackExecutor])(
      event: S3EventNotification,
      eventType: EventType): Unit Or Every[AutomationError] = {
    val events =
      event.getRecords.asScala.toSeq.map(e =>
        S3File(e.getS3.getBucket.getName, e.getS3.getObject.getKey, e.getS3.getObject.getVersionId, eventType))
    val cfClients = loadClients(events)

    //Accumulate errors into a single String for Lambda Exception or return Unit (Java Void) if no errors.
    (for {
      result <- events.map(
        s3File =>
          handler(cfClients.apply(EventProcessor.accountNumberParser(s3File.key)), s3File, stackExecutors(eventType))
      )
    } yield result.badMap(y => One(y))).combined.map(_ => ())
  }

  /**
    * Handles a stack launch, capturing any exceptions and turning them into Bad[StackConfigError] as the underlying
    * executors do not throw exceptions.
    *
    * @param s3ContentString
    * @param snsClient
    * @param externalSNSArn
    * @param semanticStackNaming
    * @param cfClient
    * @param s3File
    * @param stackExecutor
    * @return
    */
  def handleStack(s3ContentString: S3File => Try[String],
                  snsClient: AmazonSNS,
                  externalSNSArn: ExternalNotifySNSArn,
                  semanticStackNaming: Boolean)(cfClient: AmazonCloudFormation,
                                                s3File: S3File,
                                                stackExecutor: StackExecutor): Unit Or AutomationError = {
    val stackConfig: StackConfig Or AutomationError = (for {
      contentString <- s3ContentString(s3File)
      yamlJson      <- parser.parse(contentString).toTry
      config        <- yamlJson.as[StackConfig](StackConfig.decoder(s3File.key)).toTry
    } yield {
      if (semanticStackNaming) config.copy(stackName = StackConfig.semanticStackName(s3File.key)) else config
    }).fold(
      e =>
        Bad(StackConfigError(s"Failed to retrieve or parse stack file for: ${s3File.key}. Details: ${e.getMessage}")),
      g => Good(g)
    )

    val externalNotifier: (StackConfig) => Unit Or AutomationError = config =>
      externalSNSArn.fold[Unit Or AutomationError](Good()) { arn =>
        try {
          val extEvent =
            ExternalNotification(
              s3File.eventType,
              EventProcessor.accountNumberParser(s3File.key),
              config.stackName,
              s3File.key,
              config.template,
              s3File.versionId,
              s3File.bucket,
              config.tags
            )
          snsClient.publish(arn, extEvent.asJson.noSpaces, "Automated CF Action")
          Good(())
        } catch {
          case t: Throwable =>
            logger.error("External notification failed", t)
            Bad(StackError(
              s"Stack operation succeeded, but external notification failed for: ${s3File.key}. Error Details: ${t.getMessage}"))
        }
    }

    for {
      config <- stackConfig
      _      <- stackExecutor.execute(cfClient, config, s3File)
      _      <- externalNotifier(config)
    } yield ()
  }

  /**
    * Each S3File can refer to a different AWS Account. This function creates a Map of ClientID -> CloudFormationClients
    * from the given sequence of S3File events.
    *
    * @param events
    * @param assumeRoleName
    * @param stsClient
    * @return
    */
  def clientLoader(assumeRoleName: AssumeRoleName, stsClient: AWSSecurityTokenService)(
      events: Seq[S3File]): Map[String, AmazonCloudFormation] =
    events
      .groupBy[String](x => accountNumberParser(x.key))
      .keySet
      .map { accountId =>
        val assumeRoleArn: String = s"arn:aws:iam::$accountId:role/$assumeRoleName"
        val credentialsProvider: AWSCredentialsProvider =
          new STSAssumeRoleSessionCredentialsProvider.Builder(assumeRoleArn, "CF-Automation")
            .withStsClient(stsClient)
            .build()

        accountId -> AmazonCloudFormationClientBuilder.standard().withCredentials(credentialsProvider).build()
      }
      .toMap

  /**
    * Given an S3 Client and a given S3File (where the file defines the event type on the file, the versionId, bucket,
    * and key this will fetch that piece of content at the given version and return a Try[String] where the String is
    * the contents of the file.
    *
    * @param s3Client
    * @param s3File
    * @return
    */
  def getS3ContentAsString(s3Client: AmazonS3)(s3File: S3File): Try[String] = Try {
    val versionId = s3File.eventType match {
      case DeletedEvent =>
        s3Client
          .listVersions(
            new ListVersionsRequest()
              .withBucketName(s3File.bucket)
              .withKeyMarker(s3File.key)
              .withVersionIdMarker(s3File.versionId)
              .withMaxResults(1)
          )
          .getVersionSummaries
          .asScala
          .filter(_.getKey == s3File.key)
          .map(_.getVersionId)
          .head

      case CreateUpdateEvent => s3File.versionId
    }
    val s3ObjReq = new GetObjectRequest(s3File.bucket, s3File.key, versionId)
    val s3Obj    = s3Client.getObject(s3ObjReq)
    try {
      IOUtils.toString(s3Obj.getObjectContent)
    } catch {
      case e: IOException =>
        throw new SdkClientException("Error streaming content from S3 during download")
    } finally IOUtils.closeQuietly(s3Obj, null) //null here makes the AWS SDK fall back to the default logger.
  }
}
