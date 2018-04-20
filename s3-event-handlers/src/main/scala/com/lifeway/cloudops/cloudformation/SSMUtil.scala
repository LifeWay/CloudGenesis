package com.lifeway.cloudops.cloudformation

import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest
import org.scalactic.{Bad, Good, Or}

import scala.util.{Failure, Success, Try}

trait SSMUtil {
  val ssm = AWSSimpleSystemsManagementClientBuilder.defaultClient()

  def getSSMParam(path: String): String Or SSMError = {
    val req: GetParameterRequest = new GetParameterRequest().withName(path).withWithDecryption(true)
    Try {
      ssm.getParameter(req).getParameter.getValue
    } match {
      case Success(value) => Good(value)
      case Failure(t)     => Bad(SSMDefaultError(t.getMessage))
    }
  }
}

sealed trait SSMError
case class SSMDefaultError(msg: String) extends SSMError

object DefaultSSMUtil extends SSMUtil
