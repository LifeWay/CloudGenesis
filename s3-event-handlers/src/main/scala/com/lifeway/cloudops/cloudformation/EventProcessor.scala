package com.lifeway.cloudops.cloudformation

import java.io.IOException

import com.amazonaws.{AmazonServiceException, SdkClientException}
import com.amazonaws.auth.{
  AWSCredentialsProvider,
  AWSStaticCredentialsProvider,
  BasicAWSCredentials,
  STSAssumeRoleSessionCredentialsProvider
}
import com.amazonaws.services.cloudformation.{AmazonCloudFormation, AmazonCloudFormationClientBuilder}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client, AmazonS3ClientBuilder}
import com.amazonaws.services.s3.model.{GetObjectRequest, ListVersionsRequest}
import com.amazonaws.services.securitytoken.AWSSecurityTokenService
import com.amazonaws.services.sns.AmazonSNS
import com.amazonaws.util.IOUtils
import io.circe.syntax._
import io.circe.yaml.parser
import org.scalactic.{Or, _}
import com.lifeway.cloudops.cloudformation.Types._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * Given an event (s3File) and event Type, process the event returning Unit or AutomationError
  */
trait EventProcessor {
  val processEvent: S3File => Unit Or AutomationError
}

// $COVERAGE-OFF$
/**
  * Sets up the event processor such that we can handle:
  * 1.) Parsing the Stack file
  * 2.) Creating a CF client using assumed role in the destination account.
  * 3.) Notifying external system after event is successfully processed.
  *
  * @param stsClient
  * @param s3Client
  * @param snsClient
  * @param externalSNSArn
  * @param semanticStackNaming
  * @param stackExecutors
  * @param assumeRoleName
  */
class EventProcessorDefaultFunctions(stsClient: AWSSecurityTokenService,
                                     s3Client: AmazonS3,
                                     snsClient: AmazonSNS,
                                     externalSNSArn: ExternalNotifySNSArn,
                                     semanticStackNaming: SemanticStackNamingEnabled,
                                     stackExecutors: Map[EventType, StackExecutor],
                                     assumeRoleName: AssumeRoleName)
    extends EventProcessor {
  override val processEvent: S3File => Unit Or AutomationError = {
    val s3Content    = EventProcessor.getS3ContentAsString(s3Client) _
    val s3FileExists = EventProcessor.checkFileExists(s3Client) _
    val clientLoader = EventProcessor.clientLoader(assumeRoleName, stsClient) _
    EventProcessor.handleStack(s3Content,
                               s3FileExists,
                               clientLoader,
                               snsClient,
                               externalSNSArn,
                               semanticStackNaming,
                               stackExecutors)
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
    * Handles a stack launch or delete, capturing any exceptions and turning them into Bad[ErrorType] as the underlying
    * executors do not throw exceptions.
    *
    * @param s3ContentString
    * @param snsClient
    * @param externalSNSArn
    * @param semanticStackNaming
    * @param s3File
    * @param stackExecutors
    * @return
    */
  def handleStack(s3ContentString: S3File => Try[String],
                  s3FileExists: (String, String) => Boolean Or AutomationError,
                  loadClient: S3File => AmazonCloudFormation,
                  snsClient: AmazonSNS,
                  externalSNSArn: ExternalNotifySNSArn,
                  semanticStackNaming: Boolean,
                  stackExecutors: Map[EventType, StackExecutor])(s3File: S3File): Unit Or AutomationError = {
    val cfClient = loadClient(s3File)
    val stackConfig: StackConfig Or AutomationError = (for {
      contentString <- s3ContentString(s3File)
      yamlJson      <- parser.parse(contentString).toTry
      config        <- yamlJson.as[StackConfig](StackConfig.decoder(s3File.key)).toTry
    } yield {
      if (semanticStackNaming) config.copy(stackName = StackConfig.semanticStackName(s3File.key)) else config
    }).fold(
      {
        case e: AmazonServiceException if e.getStatusCode >= 500 =>
          logger.error(s"AWS 500 Service Exception: Failed to retrieve stack file: ${s3File.key}.", e)
          Bad(ServiceError(s"Failed to retrieve stack file: ${s3File.key} due to: ${e.getMessage}"))
        case e: Throwable =>
          Bad(StackConfigError(s"Failed to retrieve or parse stack file for: ${s3File.key}. Details: ${e.getMessage}"))
      },
      stackConfig => {
        s3FileExists(s3File.bucket, s"templates/${stackConfig.template}").flatMap(
          exists =>
            if (exists) Good(stackConfig)
            else
              Bad(
                StackConfigError(
                  s"Invalid template path: ${stackConfig.template} does not exist in the templates directory."))
        )
      }
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
      _      <- stackExecutors(s3File.eventType).execute(cfClient, config, s3File)
      _      <- externalNotifier(config)
    } yield ()
  }

  /**
    * Each S3File can refer to a different AWS Account. This function creates a Map of ClientID -> CloudFormationClients
    * from the given sequence of S3File events.
    *
    * @param assumeRoleName
    * @param stsClient
    * @return
    */
  def clientLoader(assumeRoleName: AssumeRoleName, stsClient: AWSSecurityTokenService)(
      event: S3File): AmazonCloudFormation = {
    val accountId             = accountNumberParser(event.key)
    val assumeRoleArn: String = s"arn:aws:iam::$accountId:role/$assumeRoleName"
    val credentialsProvider: AWSCredentialsProvider =
      new STSAssumeRoleSessionCredentialsProvider.Builder(assumeRoleArn, "CloudFormation-GitOps")
        .withStsClient(stsClient)
        .build()

    AmazonCloudFormationClientBuilder.standard().withCredentials(credentialsProvider).build()
  }

//  def main(args: Array[String]): Unit = {
//    val creds  = new BasicAWSCredentials("AKIAIXYOQ2WWQRWA42FQ", "xnpBgDGVFuqc+4OWk1ELYKyM8PtiHS0CyBf1pGbY")
//    val client = AmazonS3ClientBuilder.standard().withCredentials(new AWSStaticCredentialsProvider(creds)).build()
//
//    val res = checkFileExists(client)("cloudformation-gitops-poc-cfstack-bucket",
//                                      "templates/cloudops/user-roles/full-admin.yaml")
//    println(res)
//  }

  def checkFileExists(s3Client: AmazonS3)(bucket: String, key: String): Boolean Or AutomationError =
    try {
      Good(s3Client.doesObjectExist(bucket, key))
    } catch {
      case e: AmazonServiceException if e.getStatusCode >= 500 =>
        logger.error(s"AWS 500 Service Exception: Failed to check for existence of s3 file: $key", e)
        Bad(
          ServiceError(
            s"AWS 500 Service Exception: Failed to check for existence of s3 file: $key. Reason: ${e.getMessage}"))
      case e: Throwable =>
        logger.error(s"Failed to check for existence of s3 file: $key.", e)
        Bad(StackError(s"Failed to check for existence of s3 file: $key. Reason: ${e.getMessage}"))
    }

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
