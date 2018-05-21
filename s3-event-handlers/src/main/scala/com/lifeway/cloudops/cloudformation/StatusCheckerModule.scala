package com.lifeway.cloudops.cloudformation

import akka.actor.{ActorSystem, Scheduler}
import com.amazonaws.services.cloudformation.AmazonCloudFormation

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import akka.pattern.after
import com.amazonaws.AmazonServiceException
import com.lifeway.cloudops.cloudformation.Types.StackName
import org.scalactic._
import org.slf4j.Logger

trait StatusCheckerModule {
  val logger: Logger

  def waitForStatus(
      actorSystem: ActorSystem,
      maxRetries: Int = 100,
      maxWaitTime: Duration = 5.minutes,
      retrySpeed: FiniteDuration = 3.seconds)(statusFetcher: (AmazonCloudFormation, String) => (String, String))(
      cfClient: AmazonCloudFormation,
      id: String,
      stackName: StackName,
      waitForStatus: Types.Status,
      failIfInStatus: Seq[Types.Status]): Unit Or AutomationError = {

    implicit val ec: ExecutionContext = actorSystem.dispatcher
    implicit val sch: Scheduler       = actorSystem.scheduler

    sealed trait StatusException            extends Exception
    case object PendingException            extends StatusException
    case class FailedException(msg: String) extends StatusException

    def checkStatus: Unit = {
      val (status, reason) = statusFetcher(cfClient, id)

      if (status == waitForStatus) ()
      else if (failIfInStatus.contains(status))
        throw new FailedException(s"Unexpected stack status: $status. Reason: $reason")
      else throw PendingException
    }

    def retry(op: => Unit, delay: FiniteDuration, retries: Int): Future[Unit Or AutomationError] =
      Future(op).map(x => Good(x)) recoverWith {
        case PendingException if retries > 0 => after(delay, sch)(retry(op, delay, retries - 1))
        case FailedException(err) =>
          Future.successful(
            Bad(StackError(s"Failed to reach expected status of $waitForStatus for $stackName due to: $err")))
        case t: AmazonServiceException if t.getStatusCode >= 500 =>
          logger.error(s"AWS 500 Service Exception: Failed to reach expected status of $waitForStatus for $stackName",
                       t)
          Future.successful(
            Bad(ServiceError(
              s"AWS 500 Service Exception: Failed to reach expected status of $waitForStatus for $stackName")))
        case _ =>
          Future.successful(Bad(StackError(s"Failed to reach expected status of $waitForStatus for $stackName")))
      }

    //Retry to find final status for up to max time...
    try {
      Await.result(retry(checkStatus, retrySpeed, maxRetries), maxWaitTime)
    } catch {
      case _: Throwable =>
        Bad(
          StackError(
            s"Failed to wait to reach expected status of $waitForStatus for $stackName due to process timeout"))
    }
  }
}
