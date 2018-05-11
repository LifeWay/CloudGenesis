package com.lifeway.cloudops.cloudformation

import utest._

object TypesTests extends TestSuite {
  val tests = Tests {
    'DemuxError - {
      'ConfigurationErrorToString - {
        val e = ConfigurationError("my error msg")
        assert(e.toString == "ConfigError: my error msg")
      }

      'ParsingErrorToString - {
        val e = ParsingError("my error msg")
        assert(e.toString == "ParsingError: my error msg")
      }
    }
  }
}
