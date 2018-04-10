package com.lifeway.cloudops.cloudformation

import io.circe.{Decoder, Encoder}

case class ExternalNotification(eventType: EventType,
                                accountId: String,
                                stackName: String,
                                stackFile: String,
                                templateFile: String,
                                fileVersion: String,
                                bucket: String,
                                tags: Option[Seq[Tag]])

object ExternalNotification {
  implicit val encoder: Encoder[ExternalNotification] =
    Encoder.forProduct8("eventType",
                        "accountId",
                        "stackName",
                        "stackFile",
                        "templateFile",
                        "stackFileVersion",
                        "bucket",
                        "tags")(x =>
      (x.eventType, x.accountId, x.stackName, x.stackFile, x.templateFile, x.fileVersion, x.bucket, x.tags))

  implicit val decoder: Decoder[ExternalNotification] =
    Decoder.forProduct8("eventType",
                        "accountId",
                        "stackName",
                        "stackFile",
                        "templateFile",
                        "stackFileVersion",
                        "bucket",
                        "tags")(ExternalNotification.apply)
}
