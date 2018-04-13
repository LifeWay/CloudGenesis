package com.lifeway.cloudops.cloudformation

import java.util

import com.amazonaws.{AmazonWebServiceRequest, ResponseMetadata}
import com.amazonaws.regions.Region
import com.amazonaws.services.sns.AmazonSNS
import com.amazonaws.services.sns.model._

trait AmazonSNSTestClient extends AmazonSNS {
  override def deletePlatformApplication(
      deletePlatformApplicationRequest: DeletePlatformApplicationRequest): DeletePlatformApplicationResult = ???

  override def deleteTopic(deleteTopicRequest: DeleteTopicRequest): DeleteTopicResult = ???

  override def deleteTopic(topicArn: String): DeleteTopicResult = ???

  override def optInPhoneNumber(optInPhoneNumberRequest: OptInPhoneNumberRequest): OptInPhoneNumberResult = ???

  override def addPermission(addPermissionRequest: AddPermissionRequest): AddPermissionResult = ???

  override def addPermission(topicArn: String,
                             label: String,
                             aWSAccountIds: util.List[String],
                             actionNames: util.List[String]): AddPermissionResult = ???

  override def getCachedResponseMetadata(request: AmazonWebServiceRequest): ResponseMetadata = ???

  override def deleteEndpoint(deleteEndpointRequest: DeleteEndpointRequest): DeleteEndpointResult = ???

  override def setTopicAttributes(setTopicAttributesRequest: SetTopicAttributesRequest): SetTopicAttributesResult = ???

  override def setTopicAttributes(topicArn: String,
                                  attributeName: String,
                                  attributeValue: String): SetTopicAttributesResult = ???

  override def listTopics(listTopicsRequest: ListTopicsRequest): ListTopicsResult = ???

  override def listTopics(): ListTopicsResult = ???

  override def listTopics(nextToken: String): ListTopicsResult = ???

  override def setEndpoint(endpoint: String): Unit = ???

  override def createPlatformEndpoint(
      createPlatformEndpointRequest: CreatePlatformEndpointRequest): CreatePlatformEndpointResult = ???

  override def confirmSubscription(confirmSubscriptionRequest: ConfirmSubscriptionRequest): ConfirmSubscriptionResult =
    ???

  override def confirmSubscription(topicArn: String,
                                   token: String,
                                   authenticateOnUnsubscribe: String): ConfirmSubscriptionResult = ???

  override def confirmSubscription(topicArn: String, token: String): ConfirmSubscriptionResult = ???

  override def unsubscribe(unsubscribeRequest: UnsubscribeRequest): UnsubscribeResult = ???

  override def unsubscribe(subscriptionArn: String): UnsubscribeResult = ???

  override def getSMSAttributes(getSMSAttributesRequest: GetSMSAttributesRequest): GetSMSAttributesResult = ???

  override def setPlatformApplicationAttributes(
      setPlatformApplicationAttributesRequest: SetPlatformApplicationAttributesRequest)
    : SetPlatformApplicationAttributesResult = ???

  override def listSubscriptions(listSubscriptionsRequest: ListSubscriptionsRequest): ListSubscriptionsResult = ???

  override def listSubscriptions(): ListSubscriptionsResult = ???

  override def listSubscriptions(nextToken: String): ListSubscriptionsResult = ???

  override def getPlatformApplicationAttributes(
      getPlatformApplicationAttributesRequest: GetPlatformApplicationAttributesRequest)
    : GetPlatformApplicationAttributesResult = ???

  override def checkIfPhoneNumberIsOptedOut(
      checkIfPhoneNumberIsOptedOutRequest: CheckIfPhoneNumberIsOptedOutRequest): CheckIfPhoneNumberIsOptedOutResult =
    ???

  override def setRegion(region: Region): Unit = ???

  override def setSubscriptionAttributes(
      setSubscriptionAttributesRequest: SetSubscriptionAttributesRequest): SetSubscriptionAttributesResult = ???

  override def setSubscriptionAttributes(subscriptionArn: String,
                                         attributeName: String,
                                         attributeValue: String): SetSubscriptionAttributesResult = ???

  override def createPlatformApplication(
      createPlatformApplicationRequest: CreatePlatformApplicationRequest): CreatePlatformApplicationResult = ???

  override def createTopic(createTopicRequest: CreateTopicRequest): CreateTopicResult = ???

  override def createTopic(name: String): CreateTopicResult = ???

  override def setEndpointAttributes(
      setEndpointAttributesRequest: SetEndpointAttributesRequest): SetEndpointAttributesResult = ???

  override def subscribe(subscribeRequest: SubscribeRequest): SubscribeResult = ???

  override def subscribe(topicArn: String, protocol: String, endpoint: String): SubscribeResult = ???

  override def listSubscriptionsByTopic(
      listSubscriptionsByTopicRequest: ListSubscriptionsByTopicRequest): ListSubscriptionsByTopicResult = ???

  override def listSubscriptionsByTopic(topicArn: String): ListSubscriptionsByTopicResult = ???

  override def listSubscriptionsByTopic(topicArn: String, nextToken: String): ListSubscriptionsByTopicResult = ???

  override def getTopicAttributes(getTopicAttributesRequest: GetTopicAttributesRequest): GetTopicAttributesResult = ???

  override def getTopicAttributes(topicArn: String): GetTopicAttributesResult = ???

  override def setSMSAttributes(setSMSAttributesRequest: SetSMSAttributesRequest): SetSMSAttributesResult = ???

  override def removePermission(removePermissionRequest: RemovePermissionRequest): RemovePermissionResult = ???

  override def removePermission(topicArn: String, label: String): RemovePermissionResult = ???

  override def listEndpointsByPlatformApplication(
      listEndpointsByPlatformApplicationRequest: ListEndpointsByPlatformApplicationRequest)
    : ListEndpointsByPlatformApplicationResult = ???

  override def publish(publishRequest: PublishRequest): PublishResult = ???

  override def publish(topicArn: String, message: String): PublishResult = ???

  override def publish(topicArn: String, message: String, subject: String): PublishResult = ???

  override def listPhoneNumbersOptedOut(
      listPhoneNumbersOptedOutRequest: ListPhoneNumbersOptedOutRequest): ListPhoneNumbersOptedOutResult = ???

  override def listPlatformApplications(
      listPlatformApplicationsRequest: ListPlatformApplicationsRequest): ListPlatformApplicationsResult = ???

  override def listPlatformApplications(): ListPlatformApplicationsResult = ???

  override def getSubscriptionAttributes(
      getSubscriptionAttributesRequest: GetSubscriptionAttributesRequest): GetSubscriptionAttributesResult = ???

  override def getSubscriptionAttributes(subscriptionArn: String): GetSubscriptionAttributesResult = ???

  override def getEndpointAttributes(
      getEndpointAttributesRequest: GetEndpointAttributesRequest): GetEndpointAttributesResult = ???

  override def shutdown(): Unit = ???
}
