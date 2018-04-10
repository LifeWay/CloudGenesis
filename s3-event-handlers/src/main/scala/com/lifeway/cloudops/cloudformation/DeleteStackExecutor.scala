package com.lifeway.cloudops.cloudformation

import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.{DeleteStackRequest, DescribeStacksRequest}
import org.scalactic.{Bad, Good, Or}

/**
  * Delete stack executor. Given the StackConfig and S3File, do the delete of the given stack. If CF service raises
  * errors as part of the function call, then turn it into a Bad[StackError]. Any errors that happen Async outside of
  * the CF invoke will be caught by the SNS subscription on the CF stack itself. The job of this Lambda is not to
  * monitor the status, only to invoke the process and capture any errors that the CloudFormation service returns
  * directly as part of that invoke.
  *
  */
object DeleteStackExecutorDefaultFunctions extends StackExecutor {
  override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] =
    DeleteStackExecutor.execute
}

object DeleteStackExecutor {

  def execute(cfClient: AmazonCloudFormation, config: StackConfig, s3File: S3File): Unit Or AutomationError =
    try {
      val stackReq     = new DescribeStacksRequest().withStackName(config.stackName)
      val stackMissing = cfClient.describeStacks(stackReq).getStacks.isEmpty
      if (!stackMissing) {
        val req = new DeleteStackRequest().withStackName(config.stackName)
        cfClient.deleteStack(req)
        Good(())
      } else {
        Bad(
          StackError(
            s"Failed to delete stack: ${s3File.key}}. No stack by that stack name: ${config.stackName} exists!"))
      }
    } catch {
      case e: Throwable =>
        e.printStackTrace()
        Bad(StackError(s"Failed to delete stack: ${s3File.key} due to: ${e.getMessage}"))
    }
}
