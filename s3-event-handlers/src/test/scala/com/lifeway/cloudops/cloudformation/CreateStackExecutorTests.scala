package com.lifeway.cloudops.cloudformation

import akka.actor.ActorSystem
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model._
import org.scalactic.{Bad, Good}

import scala.concurrent.duration._
import utest._

import scala.util.{Failure, Success}

object CreateStackExecutorTests extends TestSuite {
  val testSystem  = ActorSystem("testSystem")
  val stackConfig = StackConfig("demo-stack", "demo/template.yaml", None, None)
  val s3File      = S3File("some-bucket", "test-stack.yaml", "some-version-id", CreateUpdateEvent)

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

    'waitForChangeSet - {
      def cfClientWithStatus(status: Seq[ChangeSetStatus]): AmazonCloudFormation =
        new CloudFormationTestClient {
          var calls = 0
          override def describeChangeSet(
              describeChangeSetRequest: DescribeChangeSetRequest): DescribeChangeSetResult = {
            val result = new DescribeChangeSetResult().withStatus(status(calls))
            calls = calls + 1
            result
          }
        }

      'returnGoodWhenSuccessful - {
        val cfClient = cfClientWithStatus(Seq(ChangeSetStatus.CREATE_COMPLETE))

        val result =
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient, testSystem)("cf-automation-set", "demo-stack")
        assert(result == Good(()))
      }

      'returnBadWithFailed - {
        val cfClient = cfClientWithStatus(Seq(ChangeSetStatus.FAILED))

        val result =
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient, testSystem)("cf-automation-set", "demo-stack")
        assert(
          result == Bad(StackError(
            "Failed to create change set for demo-stack due to unhandled change set status: FAILED. Reason: null")))
      }

      'returnGoodAfterWaitingCycles - {
        val cfClient = cfClientWithStatus(
          Seq(ChangeSetStatus.CREATE_PENDING,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.CREATE_COMPLETE))
        val result =
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient, testSystem)("cf-automation-set", "demo-stack")
        assert(result == Good(()))
      }

      'returnBadAfterWaitingCyclesAndFails - {
        val cfClient = cfClientWithStatus(
          Seq(ChangeSetStatus.CREATE_PENDING,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.FAILED))
        val result =
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient, testSystem)("cf-automation-set", "demo-stack")
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
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient, testSystem, maxRetries = 1)("cf-automation-set",
                                                                                                "demo-stack")
        assert(result == Bad(StackError("Failed to create change set for demo-stack")))
      }

      'returnBadIfMaxWaitExpires - {
        val cfClient = cfClientWithStatus(
          Seq(ChangeSetStatus.CREATE_PENDING,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.CREATE_IN_PROGRESS,
              ChangeSetStatus.FAILED))
        val result =
          CreateUpdateStackExecutor.waitForChangeSetReady(cfClient, testSystem, maxWaitTime = 2.seconds)(
            "cf-automation-set",
            "demo-stack")
        assert(
          result == Bad(
            StackError("Failed to create change set due to timeout waiting for change set status: demo-stack")))
      }
    }

    'determineChangeSetType - {
      'returnSuccessForCreate - {
        val cfClient = new CloudFormationTestClient {
          override def describeStacks(describeStacksRequest: DescribeStacksRequest): DescribeStacksResult =
            new DescribeStacksResult()
        }
        val result = CreateUpdateStackExecutor.determineChangeSetType(cfClient, stackConfig)
        assert(result == Success(ChangeSetType.CREATE))
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
        assert(result == Success(ChangeSetType.CREATE))
      }

      'returnSuccessForUpdate - {
        val cfClient = new CloudFormationTestClient {
          override def describeStacks(describeStacksRequest: DescribeStacksRequest): DescribeStacksResult =
            new DescribeStacksResult().withStacks(new Stack().withStackStatus(StackStatus.CREATE_COMPLETE))
        }

        val result = CreateUpdateStackExecutor.determineChangeSetType(cfClient, stackConfig)
        assert(result == Success(ChangeSetType.UPDATE))
      }

      'returnFailureForOtherErrors - {
        val cfClient = new CloudFormationTestClient {
          override def describeStacks(describeStacksRequest: DescribeStacksRequest): DescribeStacksResult =
            throw new AmazonCloudFormationException("boom")
        }

        val result = CreateUpdateStackExecutor.determineChangeSetType(cfClient, stackConfig)
        assert(result.isFailure)
      }
    }
    'execute - {
      'returnGoodWhenSuccessful - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(createChangeSetRequest: CreateChangeSetRequest): CreateChangeSetResult =
            new CreateChangeSetResult()

          override def executeChangeSet(executeChangeSetRequest: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            new ExecuteChangeSetResult()
        }

        val result = CreateUpdateStackExecutor.execute(changeSetReady = (_, _) => (_, _) => Good(()),
                                                       capabilities = _ => Seq.empty,
                                                       changeSetType = (_, _) => Success(ChangeSetType.CREATE))(
          testSystem,
          false,
          "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result.isGood)
      }

      'returnBadWhenChangeSetTypeFails - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(createChangeSetRequest: CreateChangeSetRequest): CreateChangeSetResult =
            new CreateChangeSetResult()

          override def executeChangeSet(executeChangeSetRequest: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            new ExecuteChangeSetResult()
        }

        val result = CreateUpdateStackExecutor.execute(changeSetReady = (_, _) => (_, _) => Good(()),
                                                       capabilities = _ => Seq.empty,
                                                       changeSetType = (_, _) => Failure(new Exception("boom")))(
          testSystem,
          false,
          "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(
          result == Bad(
            StackError("Failed to create change set and execute it due to failure determining change set type: boom")))
      }

      'returnBadWhenCreateChangeSetFails - {
        val cfClient = new CloudFormationTestClient {
          override def createChangeSet(createChangeSetRequest: CreateChangeSetRequest): CreateChangeSetResult =
            throw new AmazonCloudFormationException("boom")

          override def executeChangeSet(executeChangeSetRequest: ExecuteChangeSetRequest): ExecuteChangeSetResult =
            new ExecuteChangeSetResult()
        }

        val result = CreateUpdateStackExecutor.execute(changeSetReady = (_, _) => (_, _) => Good(()),
                                                       capabilities = _ => Seq.empty,
                                                       changeSetType = (_, _) => Success(ChangeSetType.CREATE))(
          testSystem,
          false,
          "some-sns-arn")(cfClient, stackConfig, s3File)

        assert(result == Bad(StackError(
          "Failed to create change set and execute it for: test-stack.yaml. Reason: boom (Service: null; Status Code: 0; Error Code: null; Request ID: null)")))

      }

      //TODO: success tests that verify tags & parameters are used, and other vars are passed as provided.
    }
  }
}
