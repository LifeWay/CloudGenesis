package com.lifeway.cloudops.cloudformation

/**
  * General Types
  */
object Types {
  type IAMCapabilityEnabled       = Boolean
  type SemanticStackNamingEnabled = Boolean
  type AssumeRoleName             = String
  type SNSName                    = String
  type AccountId                  = String
  type SNSErrorArn                = String
  type ChangeSetNamePrefix        = Option[String]
  type CFServiceRoleName          = Option[String]
  type ExternalNotifySNSArn       = Option[String]
  type TrackingTagName            = String
  type TrackingTagValuePrefix     = Option[String]
  type StackStatus                = String
  type StackId                    = String
  type ChangeSetId                = String
  type StackName                  = String
  type Status                     = String
}

/**
  *  Errors
  */
sealed trait AutomationError

case class StackConfigError(msg: String) extends AutomationError {
  override def toString: String = s"StackConfigError: $msg"
}

case class StackError(msg: String) extends AutomationError {
  override def toString: String = s"StackError: $msg"
}

case class LambdaConfigError(msg: String) extends AutomationError {
  override def toString: String = s"LambdaConfigError: $msg"
}

case class ServiceError(msg: String) extends AutomationError {
  override def toString: String = s"ServiceError: $msg"
}
