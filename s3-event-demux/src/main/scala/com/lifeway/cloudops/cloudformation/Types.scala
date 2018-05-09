package com.lifeway.cloudops.cloudformation

object Types {
  type S3FileEventsTopicArn = String
  type ErrorTopicSNSArn     = String
}

/**
  *  Errors
  */
sealed trait DemuxError

case class ConfigurationError(msg: String) extends DemuxError {
  override def toString: String = s"ConfigError: $msg"
}

case class ParsingError(msg: String) extends DemuxError {
  override def toString: String = s"ParsingError: $msg"
}
