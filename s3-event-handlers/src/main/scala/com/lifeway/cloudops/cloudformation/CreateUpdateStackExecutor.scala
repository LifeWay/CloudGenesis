package com.lifeway.cloudops.cloudformation

import akka.actor.ActorSystem
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.{
  AmazonCloudFormationException,
  Capability,
  ChangeSetNotFoundException,
  ChangeSetStatus,
  ChangeSetType,
  CreateChangeSetRequest,
  DeleteChangeSetRequest,
  DeleteStackRequest,
  DescribeChangeSetRequest,
  DescribeStacksRequest,
  ExecuteChangeSetRequest,
  StackStatus,
  Parameter => AWSParam,
  Tag => AWSTag
}
import scala.collection.JavaConverters._
import com.amazonaws.AmazonServiceException
import com.lifeway.cloudops.cloudformation.Types._
import org.scalactic._
import org.scalactic.Accumulation._
import org.slf4j.LoggerFactory

/**
  * Create stack executor. Given the StackConfig and S3File, do the delete of the given stack. If CF service raises
  * errors as part of the function call, then turn it into a Bad[StackError]. Any errors that happen Async outside of
  * the CF invoke will be caught by the SNS subscription on the CF stack itself. The job of this Lambda is not to
  * monitor the status, only to invoke the process and capture any errors that the CloudFormation service returns
  * directly as part of that invoke.
  *
  * @param actorSystem
  * @param iam
  * @param cfSNSEventsTopicName
  */
// $COVERAGE-OFF$
class CreateUpdateStackExecutorDefaultFunctions(actorSystem: ActorSystem,
                                                iam: IAMCapabilityEnabled,
                                                autoExpandCapabilityEnabled: AutoExpandCapabilityEnabled,
                                                cfServiceRoleName: CFServiceRoleName,
                                                changeSetNamePrefix: ChangeSetNamePrefix,
                                                trackingTagName: TrackingTagName,
                                                trackingTagValuePrefix: TrackingTagValuePrefix,
                                                accountId: AccountId,
                                                val cfSNSEventsTopicName: SNSName)
    extends StackExecutor {

  val trackingTagBuilder = CreateUpdateStackExecutor.trackingTagBuilder(trackingTagName, trackingTagValuePrefix) _

  val waitForStatus = CreateUpdateStackExecutor.waitForStatus(actorSystem) _
  val deleteRollbackStackIfExists =
    CreateUpdateStackExecutor.deleteRollbackCompleteStackIfExists(
      waitForStatus(CreateUpdateStackExecutor.stackStatusFetcher)) _

  override val execute: (AmazonCloudFormation, StackConfig, S3File) => Unit Or AutomationError = {
    CreateUpdateStackExecutor
      .execute()(
        waitForStatus(CreateUpdateStackExecutor.changeSetStatusFetcher),
        deleteRollbackStackIfExists,
        trackingTagBuilder,
        iam,
        autoExpandCapabilityEnabled,
        cfServiceRoleName,
        changeSetNamePrefix,
        accountId,
        cfSNSEventsTopicName
      )
  }
}
// $COVERAGE-ON$

object CreateUpdateStackExecutor extends StatusCheckerModule {
  val ChangeSetNamePostfix = "cf-deployer-automation"
  val logger               = LoggerFactory.getLogger("com.lifeway.cloudops.cloudformation.CreateUpdateStackExecutor")

  /**
    * The Region MUST be the region the CF stack is operating in.
    * The Account ID MUST be the account the DEPLOYER is operating in.
    *
    * Therefore: for every region you wish to deploy stacks into, regardless of which account, the account the
    * deployer itself operates in must have an SNS topic in each region that the deployer wishes to use in the same
    * account the deployer is in, or for other accounts the deployer deploys to.
    *
    * Cross account permissions for these topics are already generated.
    */
  val snsARNBuilder: (S3File, SNSName, AccountId) => String = (s3File, cfSNSEventsTopicName, accountId) => {
    val regionId = EventProcessor.regionIdParser(s3File.key)
    s"arn:aws:sns:$regionId:$accountId:$cfSNSEventsTopicName"
  }

  val stackStatusFetcher: (AmazonCloudFormation, StackId) => (String, String) = (cfClient, id) => {
    val stack = cfClient.describeStacks(new DescribeStacksRequest().withStackName(id)).getStacks.asScala.head
    (stack.getStackStatus, stack.getStackStatusReason)
  }

  val changeSetStatusFetcher: (AmazonCloudFormation, ChangeSetId) => (String, String) = (cfClient, id) => {
    val changeSet = cfClient.describeChangeSet(new DescribeChangeSetRequest().withChangeSetName(id))
    (changeSet.getStatus, changeSet.getStatusReason)
  }

  def execute(
      deleteChangeSetIfExists: (AmazonCloudFormation) => (ChangeSetType, String, String) => Unit Or AutomationError =
        (cf) => deleteExistingChangeSetByNameIfExists(cf),
      capabilities: (Boolean, Boolean) => Seq[Capability] = (iam, autoexpand) => capabilitiesBuilder(iam, autoexpand),
      changeSetNameBuild: (ChangeSetNamePrefix) => String = changeSetPrefix => changeSetNameBuilder(changeSetPrefix),
      changeSetType: (AmazonCloudFormation, StackConfig) => ChangeSetType Or AutomationError = determineChangeSetType,
      buildParams: StackConfig => Seq[AWSParam] Or AutomationError = s => buildParameters()(s),
      snsARNBuild: (S3File, SNSName, AccountId) => String = snsARNBuilder)(
      changeSetReady: (AmazonCloudFormation, ChangeSetId, StackName, Status, Seq[Status]) => Unit Or AutomationError,
      deleteRollbackStackIfExists: (AmazonCloudFormation, StackName) => Unit Or AutomationError,
      trackingTag: (S3File) => AWSTag,
      iam: IAMCapabilityEnabled,
      autoExpand: AutoExpandCapabilityEnabled,
      cfServiceRoleName: CFServiceRoleName,
      changeSetNamePrefix: ChangeSetNamePrefix,
      accountId: AccountId,
      cfSNSEventsTopicName: SNSName)(cfClient: AmazonCloudFormation,
                                     config: StackConfig,
                                     s3File: S3File): Unit Or AutomationError = {

    val tags: Seq[AWSTag] =
      config.tags.map(_.map(x => new AWSTag().withKey(x.key).withValue(x.value))).getOrElse(Seq.empty) :+
        trackingTag(s3File)

    val changeSetName = changeSetNameBuild(changeSetNamePrefix)

    for {
      _             <- deleteRollbackStackIfExists(cfClient, config.stackName)
      changeSetType <- changeSetType(cfClient, config)
      _             <- deleteChangeSetIfExists(cfClient)(changeSetType, config.stackName, changeSetName)
      params        <- buildParams(config)
      _ <- {
        val changeSetReq = cfServiceRoleName
          .fold(new CreateChangeSetRequest())(serviceRoleName =>
            new CreateChangeSetRequest().withRoleARN(buildStackServiceRoleArn(serviceRoleName, s3File)))
          .withCapabilities(capabilities(iam, autoExpand): _*)
          .withChangeSetName(changeSetName)
          .withChangeSetType(changeSetType)
          .withDescription(s"From CloudGenesis File: ${s3File.key}")
          .withTemplateURL(
            s"https://s3.amazonaws.com/${config.templateBucket}/${config.templatePrefix}${config.template}")
          .withNotificationARNs(snsARNBuild(s3File, cfSNSEventsTopicName, accountId))
          .withStackName(config.stackName)
          .withParameters(params: _*)
          .withTags(tags: _*)

        try {
          val changeSet = cfClient.createChangeSet(changeSetReq)
          //We must wait for the change set Status to reach CREATE_COMPLETE before continuing.
          for {
            _ <- changeSetReady(
              cfClient,
              changeSet.getId,
              config.stackName,
              ChangeSetStatus.CREATE_COMPLETE.toString,
              Seq(ChangeSetStatus.FAILED.toString, ChangeSetStatus.DELETE_COMPLETE.toString)
            )
          } yield {
            cfClient.executeChangeSet(new ExecuteChangeSetRequest().withChangeSetName(changeSet.getId))
            Good(())
          }
        } catch {
          case e: AmazonServiceException if e.getStatusCode >= 500 =>
            logger.error(s"AWS 500 Service Exception: Failed to create change set and execute it: ${s3File.key}.", e)
            Bad(ServiceError(s"Failed to create change set and execute it for: ${s3File.key}. Reason: ${e.getMessage}"))
          case e: Throwable =>
            logger.error(s"User Error: Failed to create change set and execute it: ${s3File.key}.", e)
            Bad(StackError(s"Failed to create change set and execute it for: ${s3File.key}. Reason: ${e.getMessage}"))
        }
      }
    } yield ()
  }

  def changeSetNameBuilder(namePrefix: ChangeSetNamePrefix): String =
    namePrefix.fold(ChangeSetNamePostfix)(prefix => s"$prefix-$ChangeSetNamePostfix")

  def capabilitiesBuilder(iam: IAMCapabilityEnabled, autoExpand: AutoExpandCapabilityEnabled): Seq[Capability] = {
    val iamCapabilities = Seq(Capability.CAPABILITY_NAMED_IAM, Capability.CAPABILITY_IAM)
    val autoExpandCapabilities = Seq(Capability.CAPABILITY_AUTO_EXPAND)

    (iam, autoExpand) match {
      case (true, true) => iamCapabilities ++ autoExpandCapabilities
      case (true, false) => iamCapabilities
      case (false, true) => autoExpandCapabilities
      case _ => Seq.empty[Capability]
    }
  }

  def trackingTagBuilder(trackingTagName: TrackingTagName, trackingTagValuePrefix: TrackingTagValuePrefix)(
      s3File: S3File): AWSTag = {
    val tagPrefix = trackingTagValuePrefix.getOrElse("")
    val tagValue  = s"$tagPrefix${s3File.key}".take(255)
    new AWSTag().withKey(trackingTagName).withValue(tagValue)
  }

  def buildParameters(getSSMParam: String => Or[String, SSMError] = DefaultSSMUtil.getSSMParam)(
      stackConfig: StackConfig): Seq[AWSParam] Or AutomationError = {

    val s: Seq[AWSParam Or One[AutomationError]] = stackConfig.parameters
      .map(_.map { param =>
        param.paramType.fold[AWSParam Or One[AutomationError]](
          Good(new AWSParam().withParameterKey(param.name).withParameterValue(param.value))) {
          case "SSM" =>
            getSSMParam(param.value).fold(
              ssmValue => Good(new AWSParam().withParameterKey(param.name).withParameterValue(ssmValue)), {
                case SSMDefaultError(msg) =>
                  Bad(One(StackConfigError(s"Unable to retrieve parameter from SSM: ${param.value}. Reason: $msg")))
              }
            )
          case _ => Good(new AWSParam().withParameterKey(param.name).withParameterValue(param.value))
        }
      })
      .getOrElse(Seq.empty)

    s.combined.badMap(x => StackConfigError(x.mkString))
  }

  def determineChangeSetType(cfClient: AmazonCloudFormation, config: StackConfig): ChangeSetType Or AutomationError =
    try {
      val stacks = cfClient
        .describeStacks(new DescribeStacksRequest().withStackName(config.stackName))
        .getStacks
        .asScala
        .filterNot(_.getStackStatus == "REVIEW_IN_PROGRESS")
      if (stacks.isEmpty) Good(ChangeSetType.CREATE) else Good(ChangeSetType.UPDATE)
    } catch {
      case t: AmazonCloudFormationException if t.getStatusCode == 400 && t.getMessage.contains("does not exist") =>
        Good(ChangeSetType.CREATE)
      case t: AmazonServiceException if t.getStatusCode >= 500 =>
        logger.error("AWS 500 Service Exception: Failed to determine stack change set type via describe stacks req", t)
        Bad(ServiceError("AWS 500 Service Exception: Failed to determine stack change set type"))
      case e: Throwable =>
        logger.error("Failed to determine stack change set type via describe stacks request", e)
        Bad(StackError("Failed to determine stack change set type via describe stacks request"))
    }

  def buildStackServiceRoleArn(cfServiceRoleName: String, s3File: S3File): String = {
    val accountId = EventProcessor.accountNumberParser(s3File.key)
    s"arn:aws:iam::$accountId:role/$cfServiceRoleName"
  }

  def deleteExistingChangeSetByNameIfExists(cfClient: AmazonCloudFormation)(
      changeSetType: ChangeSetType,
      stackName: String,
      changeSetName: String): Unit Or AutomationError =
    changeSetType match {
      case ChangeSetType.CREATE => Good(())
      case ChangeSetType.UPDATE =>
        val describeChangeSetReq =
          new DescribeChangeSetRequest().withChangeSetName(changeSetName).withStackName(stackName)
        try {
          val changeSetId = cfClient.describeChangeSet(describeChangeSetReq).getChangeSetId
          cfClient.deleteChangeSet(new DeleteChangeSetRequest().withChangeSetName(changeSetId))
          Good(())
        } catch {
          case _: ChangeSetNotFoundException => Good(())
          case t: AmazonServiceException if t.getStatusCode >= 500 =>
            logger.error("AWS 500 Service Exception: Failed to delete existing change set", t)
            Bad(ServiceError("AWS 500 Service Exception: Failed to delete existing change set."))
          case e: Throwable =>
            logger.error(s"Failed to delete existing change set.", e)
            Bad(StackError(s"Failed to delete existing change set. Error Details: ${e.getMessage}"))
        }
    }

  def deleteRollbackCompleteStackIfExists(
      waitForStackStatus: (AmazonCloudFormation, StackId, StackName, Status, Seq[Status]) => Unit Or AutomationError)(
      cfClient: AmazonCloudFormation,
      stackName: StackName): Unit Or AutomationError =
    try {
      val stackReq  = new DescribeStacksRequest().withStackName(stackName)
      val stackList = cfClient.describeStacks(stackReq).getStacks
      if (!stackList.isEmpty) {
        val stack = stackList.asScala.head
        if (stack.getStackStatus == StackStatus.ROLLBACK_COMPLETE.toString) {
          val req = new DeleteStackRequest().withStackName(stack.getStackId)
          cfClient.deleteStack(req)

          //we must WAIT until DELETE_COMPLETE occurs to release this function, else the new stack will fail to launch.
          waitForStackStatus(cfClient,
                             stack.getStackId,
                             stackName,
                             StackStatus.DELETE_COMPLETE.toString,
                             Seq(StackStatus.DELETE_FAILED.toString))
        } else {
          Good(())
        }
      } else Good(())
    } catch {
      case e: AmazonServiceException if e.getStatusCode >= 500 =>
        logger.error(s"AWS 500 Service Exception: Failed to lookup / delete rollback-stack stack: $stackName.", e)
        Bad(ServiceError(s"AWS 500 Service Exception: Failed to lookup / delete rollback-stack stack: $stackName."))
      case e: AmazonCloudFormationException if e.getStatusCode == 400 && e.getErrorCode == "ValidationError" =>
        //Stack does not exist by this stack name.
        Good(())
      case e: Throwable =>
        logger.error(s"Failed to lookup / delete rollback-stack stack: $stackName", e)
        Bad(StackError(s"Failed to lookup / delete rollback-stack stack: $stackName. Error: ${e.getMessage}"))
    }
}
