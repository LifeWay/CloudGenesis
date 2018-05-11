package com.lifeway.cloudops.cloudformation

import io.circe.{Decoder, Encoder, Json}

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

object S3File {
  implicit val decoder: Decoder[S3File] =
    Decoder.forProduct4("bucketName", "key", "versionId", "eventType")(S3File.apply)
  implicit val encoder: Encoder[S3File] =
    Encoder.forProduct4("bucketName", "key", "versionId", "eventType")(f => (f.bucket, f.key, f.versionId, f.eventType))
}
