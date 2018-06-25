package com.lifeway.cloudops.cloudformation

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.cloudformation.model._
import org.scalactic.{Bad, Good}
import utest._

object DeleteStackExecutorTests extends TestSuite {
  val tests = Tests {
    val stackConfig =
      StackConfig("test-stack", "demo/test-template.yaml", "cloudgenesis-demo-bucket", "templates/", None, None)
    val s3File = S3File("some-bucket", "test-stack.yaml", "some-version-id", DeletedEvent)

    'goodIfSuccessful - {
      val cfClient = new CloudFormationTestClient {
        override def describeStacks(req: DescribeStacksRequest): DescribeStacksResult =
          if (req.getStackName.equals(stackConfig.stackName))
            new DescribeStacksResult().withStacks(new Stack().withStackName("test-stack"))
          else throw new IllegalArgumentException
        override def deleteStack(req: DeleteStackRequest): DeleteStackResult =
          if (req.getStackName.equals(stackConfig.stackName))
            new DeleteStackResult()
          else throw new IllegalArgumentException
      }
      val result = DeleteStackExecutor.execute(cfClient, stackConfig, s3File)
      assert(result == Good(()))
    }

    'badIfStackDeleteRequestFails - {
      val cfClient = new CloudFormationTestClient {
        override def describeStacks(req: DescribeStacksRequest): DescribeStacksResult =
          new DescribeStacksResult().withStacks(new Stack().withStackName("test-stack"))

        override def deleteStack(deleteStackRequest: DeleteStackRequest): DeleteStackResult =
          throw new AmazonCloudFormationException("boom")
      }
      val result = DeleteStackExecutor.execute(cfClient, stackConfig, s3File)
      assert(result == Bad(StackError(
        "Failed to delete stack: test-stack.yaml due to: boom (Service: null; Status Code: 0; Error Code: null; Request ID: null)")))
    }

    'badIfAWSIsDown - {
      val cfClient = new CloudFormationTestClient {
        override def describeStacks(req: DescribeStacksRequest): DescribeStacksResult =
          new DescribeStacksResult().withStacks(new Stack().withStackName("test-stack"))

        override def deleteStack(deleteStackRequest: DeleteStackRequest): DeleteStackResult = {
          val ex = new AmazonServiceException("boom")
          ex.setStatusCode(500)
          throw ex
        }
      }
      val result = DeleteStackExecutor.execute(cfClient, stackConfig, s3File)

      assert(result == Bad(ServiceError(
        "AWS 500 Service Exception: Failed to delete stack: test-stack.yaml due to: boom (Service: null; Status Code: 500; Error Code: null; Request ID: null)")))
    }

    'badIfNoStackByName - {
      val cfClient = new CloudFormationTestClient {
        override def describeStacks(req: DescribeStacksRequest): DescribeStacksResult =
          throw new StackSetNotFoundException("no stack exists by this name")
      }
      val result = DeleteStackExecutor.execute(cfClient, stackConfig, s3File)
      assert(result == Bad(StackError(
        "Failed to delete stack: test-stack.yaml due to: no stack exists by this name (Service: null; Status Code: 0; Error Code: null; Request ID: null)")))
    }

    'badIfNoStackByNameNoException - {
      val cfClient = new CloudFormationTestClient {
        override def describeStacks(req: DescribeStacksRequest): DescribeStacksResult = new DescribeStacksResult()
      }
      val result = DeleteStackExecutor.execute(cfClient, stackConfig, s3File)
      assert(
        result == Bad(
          StackError("Failed to delete stack: test-stack.yaml. No stack by that stack name: test-stack exists!")))
    }
  }
}
