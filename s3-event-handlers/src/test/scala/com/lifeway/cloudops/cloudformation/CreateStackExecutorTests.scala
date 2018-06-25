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
  DeleteStackRequest,
  DeleteStackResult,
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
import com.lifeway.cloudops.cloudformation.Types.{StackId, StackName, Status, TrackingTagValuePrefix}
import org.scalactic._

import scala.collection.JavaConverters._
import utest._

object CreateStackExecutorTests extends TestSuite {
  val testSystem = ActorSystem("testSystem")
  val stackConfig = StackConfig(
    "demo-stack",
    "demo/template.yaml",
    "cloudgenesis-demo-bucket",
    "templates/",
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

    'snsARNBuilder - {
      'shouldBuildProperArn - {
        val testFile =
          S3File("some-bucket",
                 "stacks/my-account-name.123456789/us-west-2/my/stack/path.yaml",
                 "some-version-id",
                 CreateUpdateEvent)
        val result = CreateUpdateStackExecutor.snsARNBuilder(testFile, "some-topic-name", "987654321")
        assert(result == "arn:aws:sns:us-west-2:987654321:some-topic-name")
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

    'stackStatusFetcher - {
      'shouldReturnStackStatus - {
        val testCfClient = new CloudFormationTestClient {
          override def describeStacks(r: DescribeStacksRequest): DescribeStacksResult =
            if (r.getStackName == "expected-stack-name") {
              val stack = new Stack()
              stack.setStackStatus(StackStatus.CREATE_COMPLETE)
              stack.setStackStatusReason("User Created")
              val res = new DescribeStacksResult()
              res.setStacks(Seq(stack).asJava)
              res
            } else throw new IllegalArgumentException("unexpected value found")
        }

        val res = CreateUpdateStackExecutor.stackStatusFetcher(testCfClient, "expected-stack-name")
        assert(res == (StackStatus.CREATE_COMPLETE.toString, "User Created"))
      }
    }

    'changeSetStatusFetcher - {
      'shouldReturnChangeSetStatus - {
        val testCfClient = new CloudFormationTestClient {
          override def describeChangeSet(r: DescribeChangeSetRequest): DescribeChangeSetResult =
            if (r.getChangeSetName == "expected-stack-name") {
              val res = new DescribeChangeSetResult()
              res.setStatus(ChangeSetStatus.CREATE_COMPLETE.toString)
              res.setStatusReason("User Requested")
              res
            } else throw new IllegalArgumentException("unexpected value found")
        }

        val res = CreateUpdateStackExecutor.changeSetStatusFetcher(testCfClient, "expected-stack-name")
        assert(res == (ChangeSetStatus.CREATE_COMPLETE.toString, "User Requested"))
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

    'deleteRollbackCompleteStackIfExists - {
      val waitForStatus: (Unit Or AutomationError) => (AmazonCloudFormation,
                                                       StackId,
                                                       StackName,
                                                       Status,
                                                       Seq[Status]) => Unit Or AutomationError =
        (returnVal) => (_, _, _, _, _) => returnVal

      val testClient = new CloudFormationTestClient {
        override def describeStacks(r: DescribeStacksRequest): DescribeStacksResult =
          if (r.getStackName == "does-not-exist-no-error") {
            new DescribeStacksResult().withStacks(Seq.empty[Stack].asJava)
          } else if (r.getStackName == "does-not-exist-with-error") {
            val ex = new AmazonCloudFormationException("Stack does not exist")
            ex.setStatusCode(400)
            ex.setErrorCode("ValidationError")
            throw ex
          } else if (r.getStackName == "exists-but-not-as-rollback-complete-status") {
            val stack = new Stack()
            stack.setStackStatus(StackStatus.CREATE_COMPLETE)
            new DescribeStacksResult().withStacks(Seq(stack).asJava)
          } else if (r.getStackName == "exists-as-rollback-complete") {
            val stack = new Stack()
            stack.setStackId("existing-stack-id")
            stack.setStackStatus(StackStatus.ROLLBACK_COMPLETE)
            new DescribeStacksResult().withStacks(Seq(stack).asJava)
          } else if (r.getStackName == "aws-down") {
            val ex = new AmazonServiceException("boom")
            ex.setStatusCode(500)
            throw ex
          } else if (r.getStackName == "other-error") {
            throw new AmazonServiceException("some-problem")
          } else throw new IllegalArgumentException("unexpected value!")

        override def deleteStack(r: DeleteStackRequest): DeleteStackResult =
          if (r.getStackName == "existing-stack-id") {
            new DeleteStackResult
          } else throw new IllegalArgumentException("unexpected value!")

      }

      'shouldReturnGoodWhenStackDoesntExistNoError - {
        val res = CreateUpdateStackExecutor.deleteRollbackCompleteStackIfExists(waitForStatus(Good(())))(
          testClient,
          "does-not-exist-no-error")
        assert(res == Good(()))
      }

      'shouldReturnGoodWhenStackDoesntExistAs400Error - {
        val res = CreateUpdateStackExecutor.deleteRollbackCompleteStackIfExists(waitForStatus(Good(())))(
          testClient,
          "does-not-exist-with-error")
        assert(res == Good(()))
      }

      'shouldReturnGoodWhenStackExistsButNotRollBackCompleteStatus - {
        val res = CreateUpdateStackExecutor.deleteRollbackCompleteStackIfExists(waitForStatus(Good(())))(
          testClient,
          "exists-but-not-as-rollback-complete-status")
        assert(res == Good(()))
      }

      'shouldReturnGoodWhenStackExistAndRollBackCompletePrevStackDeletedSuccessfully - {
        val res = CreateUpdateStackExecutor.deleteRollbackCompleteStackIfExists(waitForStatus(Good(())))(
          testClient,
          "exists-as-rollback-complete")
        assert(res == Good(()))
      }

      'shouldReturnBadWhenStackExistAndRollBackCompleteButDeleteFails - {
        val res = CreateUpdateStackExecutor.deleteRollbackCompleteStackIfExists(waitForStatus(Bad(StackError("Boom"))))(
          testClient,
          "exists-as-rollback-complete")
        assert(res == Bad(StackError("Boom")))
      }

      'shouldReturnBadWhenAWSDown - {
        val res =
          CreateUpdateStackExecutor.deleteRollbackCompleteStackIfExists(waitForStatus(Good(())))(testClient, "aws-down")
        assert(
          res == Bad(
            ServiceError(s"AWS 500 Service Exception: Failed to lookup / delete rollback-stack stack: aws-down.")))
      }

      'shouldReturnBadOnError - {
        val res = CreateUpdateStackExecutor.deleteRollbackCompleteStackIfExists(waitForStatus(Good(())))(testClient,
                                                                                                         "other-error")
        assert(res == Bad(StackError(
          s"Failed to lookup / delete rollback-stack stack: other-error. Error: some-problem (Service: null; Status Code: 0; Error Code: null; Request ID: null)")))
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
        val customConfig = stackConfig.copy(templateBucket = "alt-demo-bucket", templatePrefix = "some-crazy-prefix/")

        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(req: CreateChangeSetRequest): CreateChangeSetResult =
            if (req.getCapabilities.equals(CreateUpdateStackExecutor.capabilitiesBuilder(true).map(_.toString).asJava) &&
                req.getRoleARN.equals("arn:aws:iam::123456789:role/some-role-name") &&
                req.getChangeSetName.equals(s"my-change-set-name") &&
                req.getChangeSetType.equals(ChangeSetType.CREATE.toString) &&
                req.getDescription.equals(s"From CloudGenesis File: ${s3File.key}") &&
                req.getTemplateURL.equals(
                  s"https://s3.amazonaws.com/${customConfig.templateBucket}/${customConfig.templatePrefix}${customConfig.template}") &&
                req.getNotificationARNs.equals(Seq("built-some-sns-arn-123456789").asJava) &&
                req.getStackName.equals(customConfig.stackName) &&
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
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => CreateUpdateStackExecutor.capabilitiesBuilder(true),
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters),
          snsARNBuild = (_, s, a) => s"built-$s-$a"
        )((_, _, _, _, _) => Good(()),
          (_, _) => Good(()),
          _ => autoTag,
          false,
          Some("some-role-name"),
          None,
          "123456789",
          "some-sns-arn")(cfClient, customConfig, s3File)

        assert(result.isGood)
      }

      'returnGoodWhenTypeProvidedButNotRecognized - {
        val stackConfig = StackConfig(
          "demo-stack",
          "demo/template.yaml",
          "cloudgenesis-demo-bucket",
          "templates/",
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
                req.getDescription.equals(s"From CloudGenesis File: ${s3File.key}") &&
                req.getTemplateURL.equals(
                  s"https://s3.amazonaws.com/${stackConfig.templateBucket}/${stackConfig.templatePrefix}${stackConfig.template}") &&
                req.getNotificationARNs.equals(Seq("built-some-sns-arn").asJava) &&
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
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => CreateUpdateStackExecutor.capabilitiesBuilder(true),
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters),
          snsARNBuild = (_, s, _) => s"built-$s"
        )((_, _, _, _, _) => Good(()),
          (_, _) => Good(()),
          _ => autoTag,
          false,
          Some("some-role-name"),
          None,
          "123456789",
          "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result.isGood)
      }

      'returnGoodWhenSuccessfulWithoutServiceRole - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(req: CreateChangeSetRequest): CreateChangeSetResult =
            if (req.getCapabilities.equals(CreateUpdateStackExecutor.capabilitiesBuilder(true).map(_.toString).asJava) &&
                Option(req.getRoleARN).isEmpty &&
                req.getChangeSetName.equals(s"my-change-set-name") &&
                req.getChangeSetType.equals(ChangeSetType.CREATE.toString) &&
                req.getDescription.equals(s"From CloudGenesis File: ${s3File.key}") &&
                req.getTemplateURL.equals(
                  s"https://s3.amazonaws.com/${stackConfig.templateBucket}/${stackConfig.templatePrefix}${stackConfig.template}") &&
                req.getNotificationARNs.equals(Seq("built-some-sns-arn").asJava) &&
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
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => CreateUpdateStackExecutor.capabilitiesBuilder(true),
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters),
          snsARNBuild = (_, s, _) => s"built-$s"
        )((_, _, _, _, _) => Good(()),
          (_, _) => Good(()),
          _ => autoTag,
          false,
          None,
          None,
          "123456789",
          "some-sns-arn")(cfClient, stackConfig, s3File)

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
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => Seq.empty,
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters),
          snsARNBuild = (_, s, _) => s"built-$s"
        )((_, _, _, _, _) => Bad(StackError("boom")),
          (_, _) => Good(()),
          _ => autoTag,
          false,
          Some("some-role-arn"),
          None,
          "123456789",
          "some-sns-arn")(cfClient, stackConfig, s3File)

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
          deleteChangeSetIfExists = (_) => (_, _, _) => Bad(StackError("delete go boom")),
          capabilities = _ => Seq.empty,
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters),
          snsARNBuild = (_, s, _) => s"built-$s"
        )((_, _, _, _, _) => Good(()),
          (_, _) => Good(()),
          _ => autoTag,
          false,
          Some("some-role-arn"),
          None,
          "123456789",
          "some-sns-arn")(cfClient, stackConfig, s3File)

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
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => Seq.empty,
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Bad(StackError("boom")),
          buildParams = (_) => Good(parameters),
          snsARNBuild = (_, s, _) => s"built-$s"
        )((_, _, _, _, _) => Good(()),
          (_, _) => Good(()),
          _ => autoTag,
          false,
          Some("some-role-arn"),
          None,
          "123456789",
          "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result == Bad(StackError("boom")))
      }

      'returnBadWhenDeleteRollbackStackIfExistsFails - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(createChangeSetRequest: CreateChangeSetRequest): CreateChangeSetResult =
            new CreateChangeSetResult()

          override def executeChangeSet(executeChangeSetRequest: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            new ExecuteChangeSetResult()
        }

        val result = CreateUpdateStackExecutor.execute(
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => CreateUpdateStackExecutor.capabilitiesBuilder(true),
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters),
          snsARNBuild = (_, s, _) => s"built-$s"
        )((_, _, _, _, _) => Good(()),
          (_, _) => Bad(StackError("Failed to delete existing rollback-stack")),
          _ => autoTag,
          false,
          None,
          None,
          "123456789",
          "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result == Bad(StackError("Failed to delete existing rollback-stack")))
      }

      'returnBadWhenCreateChangeSetFails - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(createChangeSetRequest: CreateChangeSetRequest): CreateChangeSetResult =
            throw new AmazonCloudFormationException("boom")

          override def executeChangeSet(executeChangeSetRequest: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            new ExecuteChangeSetResult()
        }

        val result = CreateUpdateStackExecutor.execute(
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => Seq.empty,
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters),
          snsARNBuild = (_, s, _) => s"built-$s"
        )((_, _, _, _, _) => Good(()),
          (_, _) => Good(()),
          _ => autoTag,
          false,
          Some("some-role-arn"),
          None,
          "123456789",
          "some-sns-arn")(cfClient, stackConfig, s3File)

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
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => Seq.empty,
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Bad(StackError("params boom")),
          snsARNBuild = (_, s, _) => s"built-$s"
        )((_, _, _, _, _) => Good(()),
          (_, _) => Good(()),
          _ => autoTag,
          false,
          Some("some-role-arn"),
          None,
          "123456789",
          "some-sns-arn")(cfClient, stackConfig, s3File)

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
          deleteChangeSetIfExists = (_) => (_, _, _) => Good(()),
          capabilities = _ => Seq.empty,
          changeSetNameBuild = _ => "my-change-set-name",
          changeSetType = (_, _) => Good(ChangeSetType.CREATE),
          buildParams = (_) => Good(parameters),
          snsARNBuild = (_, s, _) => s"built-$s"
        )((_, _, _, _, _) => Good(()),
          (_, _) => Good(()),
          _ => autoTag,
          false,
          Some("some-role-arn"),
          None,
          "123456789",
          "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result == Bad(ServiceError(
          "Failed to create change set and execute it for: stacks/my-account-name.123456789/my/stack/path.yaml. Reason: boom (Service: null; Status Code: 500; Error Code: null; Request ID: null)")))

      }
    }
  }
}
