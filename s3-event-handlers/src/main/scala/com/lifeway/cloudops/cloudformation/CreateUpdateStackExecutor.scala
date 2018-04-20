package com.lifeway.cloudops.cloudformation

import akka.actor.{ActorSystem, Scheduler}
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.{
  AmazonCloudFormationException,
  Capability,
  ChangeSetStatus,
  ChangeSetType,
  CreateChangeSetRequest,
  DescribeChangeSetRequest,
  DescribeStacksRequest,
  ExecuteChangeSetRequest,
  Parameter => AWSParam,
  Tag => AWSTag
}

import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import akka.pattern.after
import com.lifeway.cloudops.cloudformation.Types.{CFServiceRoleName, IAMCapabilityEnabled, SNSArn}
import org.scalactic.{Bad, Good, Or}
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

/**
  * Create stack executor. Given the StackConfig and S3File, do the delete of the given stack. If CF service raises
  * errors as part of the function call, then turn it into a Bad[StackError]. Any errors that happen Async outside of
  * the CF invoke will be caught by the SNS subscription on the CF stack itself. The job of this Lambda is not to
  * monitor the status, only to invoke the process and capture any errors that the CloudFormation service returns
  * directly as part of that invoke.
  *
  * @param actorSystem
  * @param iam
  * @param snsARN
  */
// $COVERAGE-OFF$
class CreateUpdateStackExecutorDefaultFunctions(actorSystem: ActorSystem,
                                                iam: IAMCapabilityEnabled,
                                                cfServiceRoleName: CFServiceRoleName,
                                                val snsARN: SNSArn)
    extends StackExecutor {
  override val execute: (AmazonCloudFormation, StackConfig, S3File) => Unit Or AutomationError = {
    CreateUpdateStackExecutor.execute()(actorSystem, iam, cfServiceRoleName, snsARN)
  }
}
// $COVERAGE-ON$

object CreateUpdateStackExecutor {
  val ChangeSetName = "cloudformation-automation"
  val logger        = LoggerFactory.getLogger("com.lifeway.cloudops.cloudformation.CreateUpdateStackExecutor")

  def execute(changeSetReady: (AmazonCloudFormation, ActorSystem) => (String, String) => Unit Or AutomationError =
                (cf, as) => waitForChangeSetReady(cf, as),
              capabilities: (Boolean) => Seq[Capability] = enabled => capabilitiesBuilder(enabled),
              changeSetType: (AmazonCloudFormation, StackConfig) => Try[ChangeSetType] = determineChangeSetType,
              getSSMParam: String => Or[String, SSMError] = DefaultSSMUtil.getSSMParam)(
      actorSystem: ActorSystem,
      iam: IAMCapabilityEnabled,
      cfServiceRoleName: CFServiceRoleName,
      snsARN: SNSArn)(cfClient: AmazonCloudFormation, config: StackConfig, s3File: S3File): Unit Or AutomationError = {

    val tags: Seq[AWSTag] =
      config.tags.map(_.map(x => new AWSTag().withKey(x.key).withValue(x.value))).getOrElse(Seq.empty)
    val parameters: Seq[AWSParam] =
      config.parameters
        .map(_.map{ param =>
          param.paramType.fold(new AWSParam().withParameterKey(param.name).withParameterValue(param.value)){
            case "SSM" => getSSMParam(param.value).fold(
              ssmValue => new AWSParam().withParameterKey(param.name).withParameterValue(ssmValue),
              _ => return Bad(StackConfigError(s"Unable to retrieve parameter from SSM: ${param.value}"))
            )
            case _ => new AWSParam().withParameterKey(param.name).withParameterValue(param.value)
          }
        })
        .getOrElse(Seq.empty)

    changeSetType(cfClient, config).fold(
      f => {
        logger.error("Failed to determine change set type", f)
        Bad(
          StackError(
            s"Failed to create change set and execute it due to failure determining change set type: ${f.getMessage}"))
      },
      s => {
        val changeSetReq = cfServiceRoleName
          .fold(new CreateChangeSetRequest())(serviceRoleName =>
            new CreateChangeSetRequest().withRoleARN(buildStackServiceRoleArn(serviceRoleName, s3File)))
          .withCapabilities(capabilities(iam): _*)
          .withChangeSetName(ChangeSetName)
          .withChangeSetType(s)
          .withDescription(s"From CF Automation File: ${s3File.key}")
          .withTemplateURL(s"https://s3.amazonaws.com/${s3File.bucket}/templates/${config.template}")
          .withNotificationARNs(snsARN)
          .withStackName(config.stackName)
          .withParameters(parameters: _*)
          .withTags(tags: _*)

        try {
          val changeSet = cfClient.createChangeSet(changeSetReq)
          //We must wait for the change set Status to reach CREATE_COMPLETE before continuing.
          for {
            _ <- changeSetReady(cfClient, actorSystem)(changeSet.getId, s3File.key)
          } yield cfClient.executeChangeSet(new ExecuteChangeSetRequest().withChangeSetName(changeSet.getId))
        } catch {
          case e: Throwable =>
            logger.error(s"Failed to create change set and execute it for: ${s3File.key}.", e)
            Bad(StackError(s"Failed to create change set and execute it for: ${s3File.key}. Reason: ${e.getMessage}"))
        }
      }
    )
  }

  def capabilitiesBuilder(iam: IAMCapabilityEnabled): Seq[Capability] =
    if (iam) Seq(Capability.CAPABILITY_NAMED_IAM, Capability.CAPABILITY_IAM) else Seq.empty[Capability]

  def determineChangeSetType(cfClient: AmazonCloudFormation, config: StackConfig): Try[ChangeSetType] =
    try {
      val stacks = cfClient
        .describeStacks(new DescribeStacksRequest().withStackName(config.stackName))
        .getStacks
        .asScala
        .filterNot(_.getStackStatus == "REVIEW_IN_PROGRESS")
      if (stacks.isEmpty) Success(ChangeSetType.CREATE) else Success(ChangeSetType.UPDATE)
    } catch {
      case t: AmazonCloudFormationException =>
        if (t.getStatusCode == 400 && t.getMessage.contains("does not exist"))
          Success(ChangeSetType.CREATE)
        else Failure(t)
      case e: Throwable => Failure(e)
    }

  def buildStackServiceRoleArn(cfServiceRoleName: String, s3File: S3File): String = {
    val accountId = EventProcessor.accountNumberParser(s3File.key)
    s"arn:aws:iam::$accountId:role/$cfServiceRoleName"
  }

  def waitForChangeSetReady(
      cfClient: AmazonCloudFormation,
      actorSystem: ActorSystem,
      maxRetries: Int = 100,
      maxWaitTime: Duration = 5.minutes,
      retrySpeed: FiniteDuration = 3.seconds)(changeSetArn: String, stackFile: String): Unit Or AutomationError = {

    implicit val ec: ExecutionContext = actorSystem.dispatcher
    implicit val sch: Scheduler       = actorSystem.scheduler

    sealed trait StatusException            extends Exception
    case object PendingException            extends StatusException
    case class FailedException(msg: String) extends StatusException

    def checkStatus: Unit Or AutomationError = {
      val status = cfClient.describeChangeSet(new DescribeChangeSetRequest().withChangeSetName(changeSetArn))

      ChangeSetStatus.fromValue(status.getStatus) match {
        case ChangeSetStatus.CREATE_PENDING | ChangeSetStatus.CREATE_IN_PROGRESS => throw PendingException
        case ChangeSetStatus.CREATE_COMPLETE                                     => Good(())
        case _                                                                   => throw FailedException(s"${status.getStatus}. Reason: ${status.getStatusReason}")
      }
    }

    def retry(op: => Unit Or AutomationError, delay: FiniteDuration, retries: Int): Future[Unit Or AutomationError] =
      Future(op) recoverWith {
        case PendingException if retries > 0 => after(delay, sch)(retry(op, delay, retries - 1))
        case FailedException(status) =>
          Future.successful(
            Bad(StackError(s"Failed to create change set for $stackFile due to unhandled change set status: $status")))
        case _ => Future.successful(Bad(StackError(s"Failed to create change set for $stackFile")))
      }

    //Retry to find final status for up to 5 minutes...
    try {
      Await.result(retry(checkStatus, retrySpeed, maxRetries), maxWaitTime)
    } catch {
      case _: Throwable =>
        Bad(StackError(s"Failed to create change set due to timeout waiting for change set status: $stackFile"))
    }
  }
}
