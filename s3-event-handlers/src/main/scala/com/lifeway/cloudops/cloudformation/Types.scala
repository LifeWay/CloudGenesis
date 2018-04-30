package com.lifeway.cloudops.cloudformation

import io.circe.{Decoder, Encoder, Json}

/**
  * General Types
  */
object Types {
  type IAMCapabilityEnabled       = Boolean
  type SemanticStackNamingEnabled = Boolean
  type AssumeRoleName             = String
  type SNSArn                     = String
  type ChangeSetNamePrefix        = Option[String]
  type CFServiceRoleName          = Option[String]
  type ExternalNotifySNSArn       = Option[String]
  type TrackingTagName            = String
  type TrackingTagValuePrefix     = Option[String]

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

/**
  * Event Types
  */
sealed trait EventType
case object CreateUpdateEvent extends EventType
case object DeletedEvent      extends EventType

object EventType {
  implicit val encoder: Encoder[EventType] = Encoder[EventType] {
    case CreateUpdateEvent => Json.fromString("CreateUpdateEvent")
    case DeletedEvent      => Json.fromString("DeletedEvent")
  }
  implicit val decoder: Decoder[EventType] = Decoder[EventType] { c =>
    for {
      eType <- c.as[String]
    } yield {
      eType match {
        case "CreateUpdateEvent" => CreateUpdateEvent
        case "DeletedEvent"      => DeletedEvent
      }
    }
  }
}

case class S3File(bucket: String, key: String, versionId: String, eventType: EventType)
