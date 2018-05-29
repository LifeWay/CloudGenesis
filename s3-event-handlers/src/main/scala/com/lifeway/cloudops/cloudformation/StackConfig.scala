package com.lifeway.cloudops.cloudformation

import io.circe._

case class Tag(key: String, value: String)
object Tag {
  implicit val decode: Decoder[Tag] =
    Decoder.forProduct2("Key", "Value")(Tag.apply)(Decoder.decodeString, StackConfig.yamlStringDecoder)
  implicit val encode: Encoder[Tag] = Encoder.forProduct2("Key", "Value")(x => (x.key, x.value))
}

case class Parameter(name: String, value: String, paramType: Option[String] = None)
object Parameter {
  implicit val decode: Decoder[Parameter] =
    Decoder.forProduct3("Name", "Value", "Type")(Parameter.apply)(Decoder.decodeString,
                                                                  StackConfig.yamlStringDecoder,
                                                                  Decoder.decodeOption[String])
}

case class StackConfig(stackName: String, template: String, tags: Option[Seq[Tag]], parameters: Option[Seq[Parameter]])

object StackConfig {
  val yamlStringDecoder: Decoder[String] = Decoder[String] { c =>
    val json = c.value
    //Wish I could pattern match here, but Circe hides the type classes from library users
    if (json.isString)
      json.as[String]
    else if (json.isBoolean)
      json.as[Boolean].map[String](x => if (x) "True" else "False")
    else if (json.isNumber) {
      json.as[BigDecimal].map[String](_.bigDecimal.toPlainString)
    } else
      Left(DecodingFailure("Expected Boolean, Number, or String", c.history))
  }

  val semanticStackName = (key: String) => key.split("/", 4).last.replace("/", "-").split("""\.""").head

  def decoder(fileKey: String): Decoder[StackConfig] = Decoder[StackConfig] { c =>
    for {
      stackNameOpt <- c.downField("StackName").as[Option[String]]
      template     <- c.downField("Template").as[String]
      tags         <- c.downField("Tags").as[Option[Seq[Tag]]]
      parameters   <- c.downField("Parameters").as[Option[Seq[Parameter]]]
    } yield {
      val stackName = stackNameOpt.getOrElse(semanticStackName(fileKey))
      StackConfig(stackName, template, tags, parameters)
    }
  }
}
