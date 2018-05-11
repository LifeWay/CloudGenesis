package com.lifeway.cloudops.cloudformation

import io.circe.syntax._
import io.circe.parser._
import utest._

object TypesTests extends TestSuite {
  val tests = Tests {
    'EventType - {
      'encodeCreateUpdateEventFromString - {
        val event: EventType = CreateUpdateEvent
        val json             = event.asJson.noSpaces

        assert(json == """"CreateUpdateEvent"""")
      }

      'encodeDeleteEventFromString - {
        val event: EventType = DeletedEvent
        val json             = event.asJson.noSpaces

        assert(json == """"DeletedEvent"""")
      }

      'decodeCreateUpdateEventFromString - {
        val event = """"CreateUpdateEvent""""

        val obj = parse(event).toTry.get.as[EventType].toTry.get
        assert(obj == CreateUpdateEvent)
      }

      'decodeDeleteEventFromString - {
        val event = """"DeletedEvent""""

        val obj = parse(event).toTry.get.as[EventType].toTry.get
        assert(obj == DeletedEvent)
      }
    }

    'AutomationError - {
      'StackConfigErrorToString - {
        val e = StackConfigError("my error msg")
        assert(e.toString == "StackConfigError: my error msg")
      }

      'StackErrorToString - {
        val e = StackError("my error msg")
        assert(e.toString == "StackError: my error msg")
      }

      'LambdaConfigError - {
        val e = LambdaConfigError("my error msg")
        assert(e.toString == "LambdaConfigError: my error msg")
      }

      'ServiceError - {
        val e = ServiceError("aws error")
        assert(e.toString == "ServiceError: aws error")
      }
    }
  }
}
