package com.lifeway.cloudops.cloudformation

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.ChangeSetStatus
import com.lifeway.cloudops.cloudformation.CreateStackExecutorTests.testSystem
import org.scalactic.{Bad, Good}
import org.slf4j.{Logger, LoggerFactory}
import scala.concurrent.duration._
import utest._

object StatusChecker extends StatusCheckerModule {
  override val logger: Logger = LoggerFactory.getLogger("com.lifeway.cloudops.cloudformation.StatusCheckerModule")
}

object StatusCheckerModuleTests extends TestSuite {
  val tests = Tests {
    'StatusChecker - {
      val testClient: AmazonCloudFormation = new CloudFormationTestClient {}

      val fetcherTest: (String, String) => (AmazonCloudFormation, String) => (String, String) =
        (returnVal, reason) => (_, _) => (returnVal, reason)

      val cycleFetcher: (Seq[String], String) => (AmazonCloudFormation, String) => (String, String) = {
        var calls = 0
        (returnVals, reason) => (_, _) =>
          {
            val res = returnVals(calls)
            calls = calls + 1
            (res, reason)
          }
      }

      val exceptionFetcher: (Throwable, String) => (AmazonCloudFormation, String) => (String, String) =
        (t, _) => (_, _) => throw t

      'returnGoodWhenSuccessful - {
        val result =
          StatusChecker.waitForStatus(testSystem)(fetcherTest(ChangeSetStatus.CREATE_COMPLETE.toString, ""))(
            testClient,
            "some-id",
            "demo-stack",
            ChangeSetStatus.CREATE_COMPLETE.toString,
            Seq(ChangeSetStatus.FAILED.toString))
        assert(result == Good(()))
      }

      'returnBadWithFailed - {
        val result =
          StatusChecker.waitForStatus(testSystem)(fetcherTest(ChangeSetStatus.FAILED.toString, "This is why"))(
            testClient,
            "some-id",
            "demo-stack",
            ChangeSetStatus.CREATE_COMPLETE.toString,
            Seq(ChangeSetStatus.FAILED.toString))

        assert(result == Bad(StackError(
          "Failed to reach expected status of CREATE_COMPLETE for demo-stack due to: Unexpected stack status: FAILED. Reason: This is why")))
      }

      'returnBadWhenAWSIsDown - {

        val exception = {
          val ex = new AmazonServiceException("boom")
          ex.setStatusCode(500)
          ex
        }

        val result =
          StatusChecker.waitForStatus(testSystem)(exceptionFetcher(exception, ""))(
            testClient,
            "some-id",
            "demo-stack",
            ChangeSetStatus.CREATE_COMPLETE.toString,
            Seq(ChangeSetStatus.FAILED.toString))

        assert(result == Bad(
          ServiceError("AWS 500 Service Exception: Failed to reach expected status of CREATE_COMPLETE for demo-stack")))
      }

      'returnGoodAfterWaitingCycles - {
        val callResponses = Seq(
          ChangeSetStatus.CREATE_PENDING.toString,
          ChangeSetStatus.CREATE_IN_PROGRESS.toString,
          ChangeSetStatus.CREATE_IN_PROGRESS.toString,
          ChangeSetStatus.CREATE_COMPLETE.toString
        )

        val result =
          StatusChecker.waitForStatus(testSystem, retrySpeed = 1.milli)(cycleFetcher(callResponses, ""))(
            testClient,
            "some-id",
            "demo-stack",
            ChangeSetStatus.CREATE_COMPLETE.toString,
            Seq(ChangeSetStatus.FAILED.toString))

        assert(result == Good(()))
      }

      'returnBadAfterWaitingCyclesAndFails - {
        val callResponses = Seq(
          ChangeSetStatus.CREATE_PENDING.toString,
          ChangeSetStatus.CREATE_IN_PROGRESS.toString,
          ChangeSetStatus.CREATE_IN_PROGRESS.toString,
          ChangeSetStatus.FAILED.toString
        )

        val result =
          StatusChecker.waitForStatus(testSystem, retrySpeed = 1.milli)(cycleFetcher(callResponses, "this is why"))(
            testClient,
            "some-id",
            "demo-stack",
            ChangeSetStatus.CREATE_COMPLETE.toString,
            Seq(ChangeSetStatus.FAILED.toString))

        assert(result == Bad(StackError(
          "Failed to reach expected status of CREATE_COMPLETE for demo-stack due to: Unexpected stack status: FAILED. Reason: this is why")))
      }

      'returnBadIfCyclesRetriesTooManyTimes - {
        val callResponses = Seq(
          ChangeSetStatus.CREATE_PENDING.toString,
          ChangeSetStatus.CREATE_IN_PROGRESS.toString,
          ChangeSetStatus.CREATE_IN_PROGRESS.toString,
          ChangeSetStatus.FAILED.toString
        )

        val result =
          StatusChecker.waitForStatus(testSystem, maxRetries = 1, retrySpeed = 1.milli)(
            cycleFetcher(callResponses, ""))(testClient,
                                             "some-id",
                                             "demo-stack",
                                             ChangeSetStatus.CREATE_COMPLETE.toString,
                                             Seq(ChangeSetStatus.FAILED.toString))

        assert(result == Bad(StackError("Failed to reach expected status of CREATE_COMPLETE for demo-stack")))
      }

      'returnBadIfMaxWaitExpires - {
        val callResponses = Seq(
          ChangeSetStatus.CREATE_PENDING.toString,
          ChangeSetStatus.CREATE_IN_PROGRESS.toString,
          ChangeSetStatus.CREATE_IN_PROGRESS.toString,
          ChangeSetStatus.FAILED.toString
        )

        val result =
          StatusChecker.waitForStatus(testSystem, maxWaitTime = 5.millis, retrySpeed = 25.millis)(
            cycleFetcher(callResponses, ""))(testClient,
                                             "some-id",
                                             "demo-stack",
                                             ChangeSetStatus.CREATE_COMPLETE.toString,
                                             Seq(ChangeSetStatus.FAILED.toString))

        assert(
          result == Bad(StackError(
            "Failed to wait to reach expected status of CREATE_COMPLETE for demo-stack due to process timeout")))
      }

    }
  }
}
