package com.lifeway.cloudops.cloudformation

import com.amazonaws.{AmazonWebServiceRequest, ResponseMetadata}
import com.amazonaws.regions.Region
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model._
import com.amazonaws.services.cloudformation.waiters.AmazonCloudFormationWaiters

trait CloudFormationTestClient extends AmazonCloudFormation {
  override def createStackInstances(
      createStackInstancesRequest: CreateStackInstancesRequest): CreateStackInstancesResult = ???

  override def signalResource(signalResourceRequest: SignalResourceRequest): SignalResourceResult = ???

  override def listStackSetOperationResults(
      listStackSetOperationResultsRequest: ListStackSetOperationResultsRequest): ListStackSetOperationResultsResult =
    ???

  override def describeStacks(describeStacksRequest: DescribeStacksRequest): DescribeStacksResult = ???

  override def describeStacks(): DescribeStacksResult = ???

  override def describeStackResource(
      describeStackResourceRequest: DescribeStackResourceRequest): DescribeStackResourceResult = ???

  override def listStackInstances(listStackInstancesRequest: ListStackInstancesRequest): ListStackInstancesResult = ???

  override def createStack(createStackRequest: CreateStackRequest): CreateStackResult = ???

  override def getCachedResponseMetadata(request: AmazonWebServiceRequest): ResponseMetadata = ???

  override def executeChangeSet(executeChangeSetRequest: ExecuteChangeSetRequest): ExecuteChangeSetResult = ???

  override def listStacks(listStacksRequest: ListStacksRequest): ListStacksResult = ???

  override def listStacks(): ListStacksResult = ???

  override def updateStackInstances(
      updateStackInstancesRequest: UpdateStackInstancesRequest): UpdateStackInstancesResult = ???

  override def listImports(listImportsRequest: ListImportsRequest): ListImportsResult = ???

  override def updateStack(updateStackRequest: UpdateStackRequest): UpdateStackResult = ???

  override def listStackResources(listStackResourcesRequest: ListStackResourcesRequest): ListStackResourcesResult = ???

  override def describeStackResources(
      describeStackResourcesRequest: DescribeStackResourcesRequest): DescribeStackResourcesResult = ???

  override def estimateTemplateCost(
      estimateTemplateCostRequest: EstimateTemplateCostRequest): EstimateTemplateCostResult = ???

  override def estimateTemplateCost(): EstimateTemplateCostResult = ???

  override def createChangeSet(createChangeSetRequest: CreateChangeSetRequest): CreateChangeSetResult = ???

  override def describeStackEvents(describeStackEventsRequest: DescribeStackEventsRequest): DescribeStackEventsResult =
    ???

  override def updateStackSet(updateStackSetRequest: UpdateStackSetRequest): UpdateStackSetResult = ???

  override def listChangeSets(listChangeSetsRequest: ListChangeSetsRequest): ListChangeSetsResult = ???

  override def validateTemplate(validateTemplateRequest: ValidateTemplateRequest): ValidateTemplateResult = ???

  override def describeStackInstance(
      describeStackInstanceRequest: DescribeStackInstanceRequest): DescribeStackInstanceResult = ???

  override def describeChangeSet(describeChangeSetRequest: DescribeChangeSetRequest): DescribeChangeSetResult = ???

  override def shutdown(): Unit = ???

  override def deleteStackSet(deleteStackSetRequest: DeleteStackSetRequest): DeleteStackSetResult = ???

  override def getStackPolicy(getStackPolicyRequest: GetStackPolicyRequest): GetStackPolicyResult = ???

  override def setEndpoint(endpoint: String): Unit = ???

  override def listExports(listExportsRequest: ListExportsRequest): ListExportsResult = ???

  override def continueUpdateRollback(
      continueUpdateRollbackRequest: ContinueUpdateRollbackRequest): ContinueUpdateRollbackResult = ???

  override def describeAccountLimits(
      describeAccountLimitsRequest: DescribeAccountLimitsRequest): DescribeAccountLimitsResult = ???

  override def stopStackSetOperation(
      stopStackSetOperationRequest: StopStackSetOperationRequest): StopStackSetOperationResult = ???

  override def setStackPolicy(setStackPolicyRequest: SetStackPolicyRequest): SetStackPolicyResult = ???

  override def waiters(): AmazonCloudFormationWaiters = ???

  override def cancelUpdateStack(cancelUpdateStackRequest: CancelUpdateStackRequest): CancelUpdateStackResult = ???

  override def setRegion(region: Region): Unit = ???

  override def deleteStack(deleteStackRequest: DeleteStackRequest): DeleteStackResult = ???

  override def getTemplate(getTemplateRequest: GetTemplateRequest): GetTemplateResult = ???

  override def listStackSets(listStackSetsRequest: ListStackSetsRequest): ListStackSetsResult = ???

  override def listStackSetOperations(
      listStackSetOperationsRequest: ListStackSetOperationsRequest): ListStackSetOperationsResult = ???

  override def getTemplateSummary(getTemplateSummaryRequest: GetTemplateSummaryRequest): GetTemplateSummaryResult = ???

  override def getTemplateSummary: GetTemplateSummaryResult = ???

  override def deleteStackInstances(
      deleteStackInstancesRequest: DeleteStackInstancesRequest): DeleteStackInstancesResult = ???

  override def createStackSet(createStackSetRequest: CreateStackSetRequest): CreateStackSetResult = ???

  override def updateTerminationProtection(
      updateTerminationProtectionRequest: UpdateTerminationProtectionRequest): UpdateTerminationProtectionResult = ???

  override def deleteChangeSet(deleteChangeSetRequest: DeleteChangeSetRequest): DeleteChangeSetResult = ???

  override def describeStackSet(describeStackSetRequest: DescribeStackSetRequest): DescribeStackSetResult = ???

  override def describeStackSetOperation(
      describeStackSetOperationRequest: DescribeStackSetOperationRequest): DescribeStackSetOperationResult = ???
}
