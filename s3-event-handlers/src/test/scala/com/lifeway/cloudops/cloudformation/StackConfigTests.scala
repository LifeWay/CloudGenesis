package com.lifeway.cloudops.cloudformation

import io.circe.yaml.parser
import utest._

object StackConfigTests extends TestSuite {
  val tests = Tests {
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
          parser.parse(yaml).map(_.as[StackConfig](StackConfig.decoder("some/file/key.yaml"))).toTry.get.toTry.get

        val expected = StackConfig(
          "my-stack-name",
          "demo/demo-role.yaml",
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
            .map(_.as[StackConfig](StackConfig.decoder("stacks/acct.123456789/us-west-2/some/random/stack.yaml")))
            .toTry
            .get
            .toTry
            .get

        val expected = StackConfig(
          "some-random-stack",
          "demo/demo-role.yaml",
          Some(Seq(Tag("Thing", "appA"), Tag("Owner", "ProductOwner"), Tag("Environment", "dev"))),
          Some(Seq(Parameter("Environment", "dev")))
        )

        assert(decoded == expected)
      }
    }
  }
}
