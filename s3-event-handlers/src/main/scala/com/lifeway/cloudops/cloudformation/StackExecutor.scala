package com.lifeway.cloudops.cloudformation

import com.amazonaws.services.cloudformation.AmazonCloudFormation
import org.scalactic.Or

/**
  * The base trait that every stack executor must implement.
  */
trait StackExecutor {
  val execute: (AmazonCloudFormation, StackConfig, S3File) => Unit Or AutomationError
}
