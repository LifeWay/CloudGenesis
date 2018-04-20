package com.lifeway.cloudops.cloudformation
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement
import com.amazonaws.services.simplesystemsmanagement.model.{GetParameterRequest, GetParameterResult}
import org.scalactic.{Bad, Good}
import utest._

object SSMUtilTests extends TestSuite {

  class SuccessfulAmazonSSMTestClient extends AmazonSSMTestClient
  class FailedAmazonSSMTestClient extends AmazonSSMTestClient {
    override def getParameter(getParameterRequest: GetParameterRequest): GetParameterResult = {
      throw new RuntimeException("BOOM!")
    }
  }

  object GSSMUtil extends SSMUtil {
    override val ssm: AWSSimpleSystemsManagement = new SuccessfulAmazonSSMTestClient
  }
  object ESSMUtil extends SSMUtil {
    override val ssm: AWSSimpleSystemsManagement = new FailedAmazonSSMTestClient
  }

  val tests = Tests {

    'ssm - {
      'successfullyReturnSSMValue - {
        val ssmResp = GSSMUtil.getSSMParam("/some/path")
        assert(ssmResp == Good("successful-ssm-value"))
      }

      'returnBadWhenClientFails - {
        val ssmResp = ESSMUtil.getSSMParam("/some/path")
        assert(ssmResp == Bad(SSMDefaultError("BOOM!")))
      }
    }
  }
}
