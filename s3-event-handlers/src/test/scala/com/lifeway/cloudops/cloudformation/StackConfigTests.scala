package com.lifeway.cloudops.cloudformation

import io.circe.yaml.parser
import utest._

object StackConfigTests extends TestSuite {
  val tests = Tests {

    val s3File =
      S3File("cloudgenesis-demo-bucket",
             "stacks/my-account-name.123456789/us-east-1/my/stack/path.yaml",
             "some-version-id",
             CreateUpdateEvent)

    'tagDecoder - {
      'workWithStrings - {
        val yaml =
          """
            |Key: Environment
            |Value: dev
          """.stripMargin.trim

        val decoded: Tag = parser.parse(yaml).map(_.as[Tag]).toTry.get.toTry.get
        assert(decoded.value == "dev")
        assert(decoded.key == "Environment")
      }
      'workWithNumbers - {
        val yaml =
          """
            |Key: Thing
            |Value: 5
          """.stripMargin.trim

        val decoded: Tag = parser.parse(yaml).map(_.as[Tag]).toTry.get.toTry.get
        assert(decoded.value == "5")
        assert(decoded.key == "Thing")
      }
      'workWithBooleanTrue - {
        val yaml =
          """
            |Key: Thing
            |Value: true
          """.stripMargin.trim

        val decoded: Tag = parser.parse(yaml).map(_.as[Tag]).toTry.get.toTry.get
        assert(decoded.value == "True")
        assert(decoded.key == "Thing")
      }

      'workWithBooleanFalse - {
        val yaml =
          """
            |Key: Thing
            |Value: false
          """.stripMargin.trim

        val decoded: Tag = parser.parse(yaml).map(_.as[Tag]).toTry.get.toTry.get
        assert(decoded.value == "False")
        assert(decoded.key == "Thing")
      }
    }

    'parameterDecoder - {
      'workWithStrings - {
        val yaml =
          """
            |Name: Environment
            |Value: dev
          """.stripMargin.trim

        val decoded: Parameter = parser.parse(yaml).map(_.as[Parameter]).toTry.get.toTry.get
        assert(decoded.value == "dev")
        assert(decoded.name == "Environment")
      }
      'workWithNumbers - {
        val yaml =
          """
            |Name: Thing
            |Value: 5
          """.stripMargin.trim

        val decoded: Parameter = parser.parse(yaml).map(_.as[Parameter]).toTry.get.toTry.get
        assert(decoded.value == "5")
        assert(decoded.name == "Thing")
      }
      'workWithBoolean - {
        val yaml =
          """
            |Name: Thing
            |Value: true
          """.stripMargin.trim

        val decoded: Parameter = parser.parse(yaml).map(_.as[Parameter]).toTry.get.toTry.get
        assert(decoded.value == "True")
        assert(decoded.name == "Thing")
      }
    }

    'stackConfigDecoder - {
      'useStackNameProvided - {
        val yaml =
          """
            |StackName: my-stack-name
            |Template: demo/demo-role.yaml
            |Tags:
            | - Key: Thing
            |   Value: appA
            | - Key: Owner
            |   Value: ProductOwner
            | - Key: Environment
            |   Value: dev
            |Parameters:
            | - Name: Environment
            |   Value: dev
          """.stripMargin.trim
        val decoded: StackConfig =
          parser.parse(yaml).map(_.as[StackConfig](StackConfig.decoder(s3File))).toTry.get.toTry.get

        val expected = StackConfig(
          "my-stack-name",
          "demo/demo-role.yaml",
          "cloudgenesis-demo-bucket",
          "templates/",
          Some(Seq(Tag("Thing", "appA"), Tag("Owner", "ProductOwner"), Tag("Environment", "dev"))),
          Some(Seq(Parameter("Environment", "dev")))
        )

        assert(decoded == expected)
      }

      'useSemanticStackName - {
        val yaml =
          """
            |Template: demo/demo-role.yaml
            |Tags:
            | - Key: Thing
            |   Value: appA
            | - Key: Owner
            |   Value: ProductOwner
            | - Key: Environment
            |   Value: dev
            |Parameters:
            | - Name: Environment
            |   Value: dev
          """.stripMargin.trim

        val decoded: StackConfig =
          parser
            .parse(yaml)
            .map(_.as[StackConfig](
              StackConfig.decoder(s3File.copy(key = "stacks/acct.123456789/us-west-2/some/random/stack.yaml"))))
            .toTry
            .get
            .toTry
            .get

        val expected = StackConfig(
          "some-random-stack",
          "demo/demo-role.yaml",
          "cloudgenesis-demo-bucket",
          "templates/",
          Some(Seq(Tag("Thing", "appA"), Tag("Owner", "ProductOwner"), Tag("Environment", "dev"))),
          Some(Seq(Parameter("Environment", "dev")))
        )

        assert(decoded == expected)
      }

      'useProvidedBucket - {
        val yaml =
          """
            |Template: demo/demo-role.yaml
            |TemplateBucket: other-bucket
            |Tags:
            | - Key: Thing
            |   Value: appA
            | - Key: Owner
            |   Value: ProductOwner
            | - Key: Environment
            |   Value: dev
            |Parameters:
            | - Name: Environment
            |   Value: dev
          """.stripMargin.trim

        val decoded: StackConfig =
          parser
            .parse(yaml)
            .map(_.as[StackConfig](
              StackConfig.decoder(s3File.copy(key = "stacks/acct.123456789/us-west-2/some/random/stack.yaml"))))
            .toTry
            .get
            .toTry
            .get

        val expected = StackConfig(
          "some-random-stack",
          "demo/demo-role.yaml",
          "other-bucket",
          "templates/",
          Some(Seq(Tag("Thing", "appA"), Tag("Owner", "ProductOwner"), Tag("Environment", "dev"))),
          Some(Seq(Parameter("Environment", "dev")))
        )

        assert(decoded == expected)
      }

      'useDifferentTemplatesPath - {
        val yaml =
          """
            |Template: demo/demo-role.yaml
            |TemplateBucket: other-bucket
            |TemplatePrefix: serverless/something-else/
            |Tags:
            | - Key: Thing
            |   Value: appA
            | - Key: Owner
            |   Value: ProductOwner
            | - Key: Environment
            |   Value: dev
            |Parameters:
            | - Name: Environment
            |   Value: dev
          """.stripMargin.trim

        val decoded: StackConfig =
          parser
            .parse(yaml)
            .map(_.as[StackConfig](
              StackConfig.decoder(s3File.copy(key = "stacks/acct.123456789/us-west-2/some/random/stack.yaml"))))
            .toTry
            .get
            .toTry
            .get

        val expected = StackConfig(
          "some-random-stack",
          "demo/demo-role.yaml",
          "other-bucket",
          "serverless/something-else/",
          Some(Seq(Tag("Thing", "appA"), Tag("Owner", "ProductOwner"), Tag("Environment", "dev"))),
          Some(Seq(Parameter("Environment", "dev")))
        )

        assert(decoded == expected)
      }
    }
  }
}
