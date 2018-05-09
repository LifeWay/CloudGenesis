package com.lifeway.cloudops.cloudformation

import akka.actor.ActorSystem
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.{
  AmazonCloudFormationException,
  Capability,
  ChangeSetNotFoundException,
  ChangeSetStatus,
  ChangeSetType,
  CreateChangeSetRequest,
  CreateChangeSetResult,
  DeleteChangeSetRequest,
  DeleteChangeSetResult,
  DescribeChangeSetRequest,
  DescribeChangeSetResult,
  DescribeStacksRequest,
  DescribeStacksResult,
  ExecuteChangeSetRequest,
  ExecuteChangeSetResult,
  Stack,
  StackStatus,
  Parameter => AWSParam,
  Tag => AWSTag
}
import com.lifeway.cloudops.cloudformation.Types.TrackingTagValuePrefix
import org.scalactic.{Bad, Good, Or}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import utest._

import scala.util.{Failure, Success}

object CreateStackExecutorTests extends TestSuite {
  val testSystem = ActorSystem("testSystem")
  val stackConfig = StackConfig(
    "demo-stack",
    "demo/template.yaml",
    Some(Seq(Tag("myTagKey", "myTagValue"), Tag("myTagKey2", "myTagValue2"))),
    Some(Seq(Parameter("myParam", "myValue"), Parameter("myBoolParam", "true")))
  )
  val s3File =
    S3File("some-bucket", "stacks/my-account-name.123456789/my/stack/path.yaml", "some-version-id", CreateUpdateEvent)

  val tests = Tests {
    'iamCapabilities - {

      'emptySetWhenNotEnabled - {
        val set = CreateUpdateStackExecutor.capabilitiesBuilder(false)
        assert(set == Seq.empty[Capability])
      }

      'bothIamCapabilitiesWhenEnabled - {
        val set = CreateUpdateStackExecutor.capabilitiesBuilder(true)
        assert(set == Seq(Capability.CAPABILITY_NAMED_IAM, Capability.CAPABILITY_IAM))
      }
    }

    'changeSetNameBuilder - {
      'returnStaticValueWhenNone - {
        val name = CreateUpdateStackExecutor.changeSetNameBuilder(None)
        assert(name == CreateUpdateStackExecutor.ChangeSetNamePostfix)
      }

      'returnWithPrefixWhenSome - {
        val name = CreateUpdateStackExecutor.changeSetNameBuilder(Some("some-crazy-prefix"))
        assert(name == s"some-crazy-prefix-${CreateUpdateStackExecutor.ChangeSetNamePostfix}")
      }
    }

    'waitForChangeSet - {
      def cfClientWithStatus(status: Seq[ChangeSetStatus]): AmazonCloudFormation =
        new CloudFormationTestClient {
          var calls = 0
          override def describeChangeSet(req: DescribeChangeSetRequest): DescribeChangeSetResult =
            if (req.getChangeSetName.equals("cf-automation-set-arn")) {
              val result = new DescribeChangeSetResult().withStatus(status(calls))
              calls = calls + 1
              result
            } else throw new IllegalArgumentException
        }

      'returnGoodWhenSuccessful - {
        val cfClient = cfClientWithStatus(Seq(ChangeSetStatus.CREATE_COMPLETE))

        val result =
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient, testSystem)("cf-automation-set-arn", "demo-stack")
        assert(result == Good(()))
      }

      'returnBadWithFailed - {
        val cfClient = cfClientWithStatus(Seq(ChangeSetStatus.FAILED))

        val result =
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient, testSystem)("cf-automation-set-arn", "demo-stack")
        assert(
          result == Bad(StackError(
            "Failed to create change set for demo-stack due to unhandled change set status: FAILED. Reason: null")))
      }

      'returnBadWhenAWSIsDown - {

        val cfClient = new CloudFormationTestClient {
          var calls = 0
          override def describeChangeSet(req: DescribeChangeSetRequest): DescribeChangeSetResult = {
            val ex = new AmazonServiceException("boom")
            ex.setStatusCode(500)
            throw ex
          }
        }

        val result =
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient, testSystem)("cf-automation-set-arn", "demo-stack")
        assert(result == Bad(
          ServiceError("AWS 500 Service Exception: Change Set failed to reach CREATE_COMPLETE status for: demo-stack")))
      }

      'returnGoodAfterWaitingCycles - {
        val cfClient = cfClientWithStatus(
          Seq(ChangeSetStatus.CREATE_PENDING,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.CREATE_COMPLETE))
        val result =
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient, testSystem, retrySpeed = 1.milli)(
            "cf-automation-set-arn",
            "demo-stack")
        assert(result == Good(()))
      }

      'returnBadAfterWaitingCyclesAndFails - {
        val cfClient = cfClientWithStatus(
          Seq(ChangeSetStatus.CREATE_PENDING,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.FAILED))
        val result =
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient, testSystem, retrySpeed = 1.milli)(
            "cf-automation-set-arn",
            "demo-stack")
        assert(
          result == Bad(StackError(
            "Failed to create change set for demo-stack due to unhandled change set status: FAILED. Reason: null")))
      }

      'returnBadIfCyclesRetriesTooManyTimes - {
        val cfClient = cfClientWithStatus(
          Seq(ChangeSetStatus.CREATE_PENDING,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.FAILED))
        val result =
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient, testSystem, maxRetries = 1, retrySpeed = 1.milli)(
            "cf-automation-set-arn",
            "demo-stack")
        assert(result == Bad(StackError("Change Set failed to reach CREATE_COMPLETE status for: demo-stack")))
      }

      'returnBadIfMaxWaitExpires - {
        val cfClient = cfClientWithStatus(
          Seq(ChangeSetStatus.CREATE_PENDING,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.FAILED))
        val result =
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient,
                                                          testSystem,
                                                          maxWaitTime = 5.millis,
                                                          retrySpeed = 25.millis)("cf-automation-set-arn", "demo-stack")
        assert(
          result == Bad(
            StackError("Failed to create change set due to timeout waiting for change set status: demo-stack")))
      }
    }

    'determineChangeSetType - {
      'returnSuccessForCreate - {
        val cfClient = new CloudFormationTestClient {
          override def describeStacks(req: DescribeStacksRequest): DescribeStacksResult =
            if (req.getStackName.equals(stackConfig.stackName))
              new DescribeStacksResult()
            else throw new IllegalArgumentException
        }
        val result = CreateUpdateStackExecutor.determineChangeSetType(cfClient, stackConfig)
        assert(result == Good(ChangeSetType.CREATE))
      }

      'returnSuccessForCreateAlternate - {
        val cfClient = new CloudFormationTestClient {
          override def describeStacks(describeStacksRequest: DescribeStacksRequest): DescribeStacksResult = {
            val exception = new AmazonCloudFormationException("blah blah blah stack does not exist ...")
            exception.setStatusCode(400)
            throw exception
          }
        }

        val result = CreateUpdateStackExecutor.determineChangeSetType(cfClient, stackConfig)
        assert(result == Good(ChangeSetType.CREATE))
      }

      'returnSuccessForUpdate - {
        val cfClient = new CloudFormationTestClient {
          override def describeStacks(describeStacksRequest: DescribeStacksRequest): DescribeStacksResult =
            new DescribeStacksResult().withStacks(new Stack().withStackStatus(StackStatus.CREATE_COMPLETE))
        }

        val result = CreateUpdateStackExecutor.determineChangeSetType(cfClient, stackConfig)
        assert(result == Good(ChangeSetType.UPDATE))
      }

      'returnServiceErrorForAWSDown - {
        val cfClient = new CloudFormationTestClient {
          override def describeStacks(describeStacksRequest: DescribeStacksRequest): DescribeStacksResult = {
            val ex = new AmazonServiceException("boom")
            ex.setStatusCode(500)
            throw ex
          }
        }

        val result = CreateUpdateStackExecutor.determineChangeSetType(cfClient, stackConfig)
        assert(result == Bad(ServiceError("AWS 500 Service Exception: Failed to determine stack change set type")))
      }

      'returnFailureForOtherErrorsFromAWS - {
        val cfClient = new CloudFormationTestClient {
          override def describeStacks(describeStacksRequest: DescribeStacksRequest): DescribeStacksResult =
            throw new AmazonCloudFormationException("boom")
        }

        val result = CreateUpdateStackExecutor.determineChangeSetType(cfClient, stackConfig)
        assert(result == Bad(StackError("Failed to determine stack change set type via describe stacks request")))
      }

      'returnFailureForOtherThrowables - {
        val cfClient = new CloudFormationTestClient {
          override def describeStacks(describeStacksRequest: DescribeStacksRequest): DescribeStacksResult =
            throw new Exception("boom")
        }

        val result = CreateUpdateStackExecutor.determineChangeSetType(cfClient, stackConfig)
        assert(result == Bad(StackError("Failed to determine stack change set type via describe stacks request")))
      }
    }

    'buildStackServiceRoleArn - {
      'buildRoleFromNameAndFile - {
        val s3File =
          S3File("bucket", "stacks/my-account-name.123456789/my/stack/path.yaml", "version-123", CreateUpdateEvent)
        val result = CreateUpdateStackExecutor.buildStackServiceRoleArn("my/role/path", s3File)

        assert(result == "arn:aws:iam::123456789:role/my/role/path")
      }
    }

    'trackingTagBuilder - {
      'buildTagWithPrefixWhenProvided - {
        val tagName                           = "my-custom-tag-name"
        val tagPrefix: TrackingTagValuePrefix = Some("https://github.com/someorg/somerepo/templates/")
        val s3File                            = S3File("some-bucket", "some/really/long/file/path.yaml", "123456789", CreateUpdateEvent)

        val res = CreateUpdateStackExecutor.trackingTagBuilder(tagName, tagPrefix)(s3File)
        assert(
          res == new AWSTag()
            .withKey(tagName)
            .withValue("https://github.com/someorg/somerepo/templates/some/really/long/file/path.yaml"))

      }

      'buildTagWithoutPrefixWhenEmpty - {
        val tagName                           = "my-custom-tag-name"
        val tagPrefix: TrackingTagValuePrefix = None
        val s3File                            = S3File("some-bucket", "some/really/long/file/path.yaml", "123456789", CreateUpdateEvent)

        val res = CreateUpdateStackExecutor.trackingTagBuilder(tagName, tagPrefix)(s3File)
        assert(
          res == new AWSTag()
            .withKey(tagName)
            .withValue("some/really/long/file/path.yaml"))
      }

      'trimTagLengthTo255Chars - {
        val tagName                           = "my-custom-tag-name"
        val tagPrefix: TrackingTagValuePrefix = Some("https://github.com/someorg/somerepo/templates/")
        val s3File = S3File(
          "some-bucket",
          "some/really/long/file/path/some/really/long/file/path/some/really/long/file/path/some/really/long/file/path/some/really/long/file/path/some/really/long/file/path/some/really/long/file/path/some/really/long/file/path/some/really/long/file/path.yaml",
          "123456789",
          CreateUpdateEvent
        )

        val res = CreateUpdateStackExecutor.trackingTagBuilder(tagName, tagPrefix)(s3File)
        assert(
          res == new AWSTag()
            .withKey(tagName)
            .withValue(
              "https://github.com/someorg/somerepo/templates/some/really/long/file/path/some/really/long/file/path/some/really/long/file/path/some/really/long/file/path/some/really/long/file/path/some/really/long/file/path/some/really/long/file/path/some/really/long/fil"))

      }
    }

    'deleteExistingChangeSetByNameIfExists - {
      'returnGoodOnChangeSetTypeCreate - {
        val res =
          CreateUpdateStackExecutor.deleteExistingChangeSetByNameIfExists(null)(ChangeSetType.CREATE, null, null)
        assert(res == Good(()))
      }

      'returnGoodOnChangeSetTypeUpdateWhenChangeSetNameDoesntExist - {
        val cfClient = new CloudFormationTestClient {
          override def describeChangeSet(describeChangeSetRequest: DescribeChangeSetRequest): DescribeChangeSetResult =
            throw new ChangeSetNotFoundException("not found")
        }

        val res =
          CreateUpdateStackExecutor.deleteExistingChangeSetByNameIfExists(cfClient)(ChangeSetType.UPDATE,
                                                                                    "stack-name",
                                                                                    "change-set-name")

        assert(res == Good(()))
      }

      'returnGoodOnChangeSetTypeUpdateWhenChangeSetNameExistsAndDeletesSuccessfully - {
        val cfClient = new CloudFormationTestClient {
          override def describeChangeSet(describeChangeSetRequest: DescribeChangeSetRequest): DescribeChangeSetResult =
            new DescribeChangeSetResult().withChangeSetId("change-set-id")

          override def deleteChangeSet(deleteChangeSetRequest: DeleteChangeSetRequest): DeleteChangeSetResult =
            if (deleteChangeSetRequest.getChangeSetName == "change-set-id") new DeleteChangeSetResult
            else throw new IllegalArgumentException
        }

        val res =
          CreateUpdateStackExecutor.deleteExistingChangeSetByNameIfExists(cfClient)(ChangeSetType.UPDATE,
                                                                                    "stack-name",
                                                                                    "change-set-name")

        assert(res == Good(()))
      }

      'returnBadIfErrorDeletingChangeSet - {
        val cfClient = new CloudFormationTestClient {
          override def describeChangeSet(describeChangeSetRequest: DescribeChangeSetRequest): DescribeChangeSetResult =
            new DescribeChangeSetResult().withStackId("some-stack-id")

          override def deleteChangeSet(deleteChangeSetRequest: DeleteChangeSetRequest): DeleteChangeSetResult =
            throw new Exception("boom")
        }

        val res =
          CreateUpdateStackExecutor.deleteExistingChangeSetByNameIfExists(cfClient)(ChangeSetType.UPDATE,
                                                                                    "stack-name",
                                                                                    "change-set-name")

        assert(res == Bad(StackError("Failed to delete existing change set. Error Details: boom")))
      }

      'returnServiceErrorIfAWSDown - {
        val cfClient = new CloudFormationTestClient {
          override def describeChangeSet(describeChangeSetRequest: DescribeChangeSetRequest): DescribeChangeSetResult =
            new DescribeChangeSetResult().withStackId("some-stack-id")

          override def deleteChangeSet(deleteChangeSetRequest: DeleteChangeSetRequest): DeleteChangeSetResult = {
            val ex = new AmazonServiceException("boom")
            ex.setStatusCode(500)
            throw ex
          }
        }

        val res =
          CreateUpdateStackExecutor.deleteExistingChangeSetByNameIfExists(cfClient)(ChangeSetType.UPDATE,
                                                                                    "stack-name",
                                                                                    "change-set-name")

        assert(res == Bad(ServiceError("AWS 500 Service Exception: Failed to delete existing change set.")))
      }
    }

    'buildParams - {
      'returnStaticParamsWithoutParamTypes - {
        val result = CreateUpdateStackExecutor.buildParameters(s => Good(s"ssm: $s"))(stackConfig)
        val expectedAwsParams: Seq[AWSParam] =
          stackConfig.parameters
            .map(_.map(x => new AWSParam().withParameterKey(x.name).withParameterValue(x.value)))
            .getOrElse(Seq.empty)

        assert(result == Good(expectedAwsParams))
      }

      'returnStaticParamsWithUnMatchedParamType - {
        val testParams = Some(
          Seq(Parameter("myParam", "myValue", Some("MadeUpType")),
              Parameter("myBoolParam", "true", Some("MadeUpType"))))
        val testConfig = stackConfig.copy(parameters = testParams)

        val result = CreateUpdateStackExecutor.buildParameters(s => Good(s"ssm: $s"))(testConfig)
        val expectedAwsParams: Seq[AWSParam] =
          testConfig.parameters
            .map(_.map(x => new AWSParam().withParameterKey(x.name).withParameterValue(x.value)))
            .getOrElse(Seq.empty)

        assert(result == Good(expectedAwsParams))
      }

      'returnSSMParamWhenSSMMatchedParamType - {
        val testParams =
          Some(Seq(Parameter("mySSMParam", "ssmParamKey", Some("SSM")), Parameter("myBoolParam", "true")))
        val testConfig = stackConfig.copy(parameters = testParams)

        val result = CreateUpdateStackExecutor.buildParameters(s => Good(s"ssm: $s"))(testConfig)
        val expectedAwsParams: Seq[AWSParam] = Seq(
          new AWSParam().withParameterKey("myBoolParam").withParameterValue("true"),
          new AWSParam().withParameterKey("mySSMParam").withParameterValue("ssm: ssmParamKey")
        )

        assert(result.isGood)
        assert(result.get.toSet == expectedAwsParams.toSet)
      }

      'returnStackConfigErrorWhenUnableToFetchSSMParam - {
        val testParams =
          Some(Seq(Parameter("mySSMParam", "ssmParamKey", Some("SSM")), Parameter("myBoolParam", "true")))
        val testConfig = stackConfig.copy(parameters = testParams)

        val result = CreateUpdateStackExecutor.buildParameters(s => Bad(SSMDefaultError("ssm: boom")))(testConfig)

        assert(result == Bad(
          StackConfigError("StackConfigError: Unable to retrieve parameter from SSM: ssmParamKey. Reason: ssm: boom")))
      }
    }

    'execute - {
      val parameters: Seq[AWSParam] =
        stackConfig.parameters
          .map(_.map(x => new AWSParam().withParameterKey(x.name).withParameterValue(x.value)))
          .getOrElse(Seq.empty)
      val tags: Seq[AWSTag] =
        stackConfig.tags.map(_.map(x => new AWSTag().withKey(x.key).withValue(x.value))).getOrElse(Seq.empty)

      val autoTag = new AWSTag().withKey("auto-tag-key").withValue("auto-tag-value")

      'returnGoodWhenSuccessfulWithServiceRole - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(req: CreateChangeSetRequest): CreateChangeSetResult =
            if (req.getCapabilities.equals(CreateUpdateStackExecutor.capabilitiesBuilder(true).map(_.toString).asJava) &&
                req.getRoleARN.equals("arn:aws:iam::123456789:role/some-role-name") &&
                req.getChangeSetName.equals(s"my-change-set-name") &&
                req.getChangeSetType.equals(ChangeSetType.CREATE.toString) &&
                req.getDescription.equals(s"From CF Automation File: ${s3File.key}") &&
                req.getTemplateURL.equals(
                  s"https://s3.amazonaws.com/${s3File.bucket}/templates/${stackConfig.template}") &&
                req.getNotificationARNs.equals(Seq("some-sns-arn").asJava) &&
                req.getStackName.equals(stackConfig.stackName) &&
                req.getParameters.equals(parameters.asJava) &&
                req.getTags.containsAll(tags.asJava) &&
                req.getTags.contains(autoTag))
              new CreateChangeSetResult().withId("this-change-set-id")
            else throw new Exception("Received unexpected params")

          override def executeChangeSet(req: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            if (req.getChangeSetName.equals("this-change-set-id")) new ExecuteChangeSetResult()
            else throw new Exception("Received unexpected params")
        }

        val result = CreateUpdateStackExecutor.execute(
          changeSetReady = (_, _) => (_, _) => Good(()),
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => CreateUpdateStackExecutor.capabilitiesBuilder(true),
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters)
        )(_ => autoTag, testSystem, false, Some("some-role-name"), None, "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result.isGood)
      }

      'returnGoodWhenTypeProvidedButNotRecognized - {
        val stackConfig = StackConfig(
          "demo-stack",
          "demo/template.yaml",
          Some(Seq(Tag("myTagKey", "myTagValue"), Tag("myTagKey2", "myTagValue2"))),
          Some(
            Seq(Parameter("myParam", "myValue"),
                Parameter("myBoolParam", "true"),
                Parameter("someParam", "someValue", Some("NO_BUENO"))))
        )
        val parameters: Seq[AWSParam] =
          stackConfig.parameters
            .map(_.map(x => new AWSParam().withParameterKey(x.name).withParameterValue(x.value)))
            .getOrElse(Seq.empty)

        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(req: CreateChangeSetRequest): CreateChangeSetResult =
            if (req.getCapabilities.equals(CreateUpdateStackExecutor.capabilitiesBuilder(true).map(_.toString).asJava) &&
                req.getRoleARN.equals("arn:aws:iam::123456789:role/some-role-name") &&
                req.getChangeSetName.equals(s"my-change-set-name") &&
                req.getChangeSetType.equals(ChangeSetType.CREATE.toString) &&
                req.getDescription.equals(s"From CF Automation File: ${s3File.key}") &&
                req.getTemplateURL.equals(
                  s"https://s3.amazonaws.com/${s3File.bucket}/templates/${stackConfig.template}") &&
                req.getNotificationARNs.equals(Seq("some-sns-arn").asJava) &&
                req.getStackName.equals(stackConfig.stackName) &&
                req.getParameters.size.equals(parameters.size) &&
                req.getTags.containsAll(tags.asJava) &&
                req.getTags.contains(autoTag))
              new CreateChangeSetResult().withId("this-change-set-id")
            else throw new Exception("Received unexpected params")

          override def executeChangeSet(req: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            if (req.getChangeSetName.equals("this-change-set-id")) new ExecuteChangeSetResult()
            else throw new Exception("Received unexpected params")
        }

        val result = CreateUpdateStackExecutor.execute(
          changeSetReady = (_, _) => (_, _) => Good(()),
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => CreateUpdateStackExecutor.capabilitiesBuilder(true),
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters)
        )(_ => autoTag, testSystem, false, Some("some-role-name"), None, "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result.isGood)
      }

      'returnGoodWhenSuccessfulWithoutServiceRole - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(req: CreateChangeSetRequest): CreateChangeSetResult =
            if (req.getCapabilities.equals(CreateUpdateStackExecutor.capabilitiesBuilder(true).map(_.toString).asJava) &&
                Option(req.getRoleARN).isEmpty &&
                req.getChangeSetName.equals(s"my-change-set-name") &&
                req.getChangeSetType.equals(ChangeSetType.CREATE.toString) &&
                req.getDescription.equals(s"From CF Automation File: ${s3File.key}") &&
                req.getTemplateURL.equals(
                  s"https://s3.amazonaws.com/${s3File.bucket}/templates/${stackConfig.template}") &&
                req.getNotificationARNs.equals(Seq("some-sns-arn").asJava) &&
                req.getStackName.equals(stackConfig.stackName) &&
                req.getParameters.equals(parameters.asJava) &&
                req.getTags.containsAll(tags.asJava) &&
                req.getTags.contains(autoTag))
              new CreateChangeSetResult().withId("this-change-set-id")
            else throw new Exception("Received unexpected params")

          override def executeChangeSet(req: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            if (req.getChangeSetName.equals("this-change-set-id")) new ExecuteChangeSetResult()
            else throw new Exception("Received unexpected params")
        }

        val result = CreateUpdateStackExecutor.execute(
          changeSetReady = (_, _) => (_, _) => Good(()),
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => CreateUpdateStackExecutor.capabilitiesBuilder(true),
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters)
        )(_ => autoTag, testSystem, false, None, None, "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result.isGood)
      }

      'returnBadWhenChangeSetReadyFails - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(createChangeSetRequest: CreateChangeSetRequest): CreateChangeSetResult =
            new CreateChangeSetResult()

          override def executeChangeSet(executeChangeSetRequest: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            new ExecuteChangeSetResult()
        }

        val result = CreateUpdateStackExecutor.execute(
          changeSetReady = (_, _) => (_, _) => Bad(StackError("boom")),
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => Seq.empty,
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters)
        )(_ => autoTag, testSystem, false, Some("some-role-arn"), None, "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result == Bad(StackError("boom")))
      }

      'returnBadIfDeleteChangeSetIfExistsFails - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(createChangeSetRequest: CreateChangeSetRequest): CreateChangeSetResult =
            new CreateChangeSetResult()

          override def executeChangeSet(executeChangeSetRequest: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            new ExecuteChangeSetResult()
        }

        val result = CreateUpdateStackExecutor.execute(
          changeSetReady = (_, _) => (_, _) => Good(()),
          deleteChangeSetIfExists = (_) => (_, _, _) => Bad(StackError("delete go boom")),
          capabilities = _ => Seq.empty,
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters)
        )(_ => autoTag, testSystem, false, Some("some-role-arn"), None, "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result == Bad(StackError("delete go boom")))
      }

      'returnBadWhenChangeSetTypeFails - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(createChangeSetRequest: CreateChangeSetRequest): CreateChangeSetResult =
            new CreateChangeSetResult()

          override def executeChangeSet(executeChangeSetRequest: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            new ExecuteChangeSetResult()
        }

        val result = CreateUpdateStackExecutor.execute(
          changeSetReady = (_, _) => (_, _) => Good(()),
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => Seq.empty,
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Bad(StackError("boom")),
          buildParams = (_) => Good(parameters)
        )(_ => autoTag, testSystem, false, Some("some-role-arn"), None, "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result == Bad(StackError("boom")))
      }

      'returnBadWhenCreateChangeSetFails - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(createChangeSetRequest: CreateChangeSetRequest): CreateChangeSetResult =
            throw new AmazonCloudFormationException("boom")

          override def executeChangeSet(executeChangeSetRequest: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            new ExecuteChangeSetResult()
        }

        val result = CreateUpdateStackExecutor.execute(
          changeSetReady = (_, _) => (_, _) => Good(()),
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => Seq.empty,
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters)
        )(_ => autoTag, testSystem, false, Some("some-role-arn"), None, "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result == Bad(StackError(
          "Failed to create change set and execute it for: stacks/my-account-name.123456789/my/stack/path.yaml. Reason: boom (Service: null; Status Code: 0; Error Code: null; Request ID: null)")))

      }

      'returnBadWhenBuildParamsFails - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(createChangeSetRequest: CreateChangeSetRequest): CreateChangeSetResult =
            new CreateChangeSetResult()

          override def executeChangeSet(executeChangeSetRequest: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            new ExecuteChangeSetResult()
        }

        val result = CreateUpdateStackExecutor.execute(
          changeSetReady = (_, _) => (_, _) => Good(()),
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => Seq.empty,
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Bad(StackError("params boom"))
        )(_ => autoTag, testSystem, false, Some("some-role-arn"), None, "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result == Bad(StackError("params boom")))
      }

      'returnServiceErrorWhenAWSDown - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(createChangeSetRequest: CreateChangeSetRequest): CreateChangeSetResult = {
            val ex = new AmazonServiceException("boom")
            ex.setStatusCode(500)
            throw ex
          }

          override def executeChangeSet(executeChangeSetRequest: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            new ExecuteChangeSetResult()
        }

        val result = CreateUpdateStackExecutor.execute(
          changeSetReady = (_, _) => (_, _) => Good(()),
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => Seq.empty,
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters)
        )(_ => autoTag, testSystem, false, Some("some-role-arn"), None, "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result == Bad(ServiceError(
          "Failed to create change set and execute it for: stacks/my-account-name.123456789/my/stack/path.yaml. Reason: boom (Service: null; Status Code: 500; Error Code: null; Request ID: null)")))

      }
    }
  }
}
