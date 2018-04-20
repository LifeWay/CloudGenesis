package com.lifeway.cloudops.cloudformation

import com.amazonaws.{AmazonWebServiceRequest, ResponseMetadata}
import com.amazonaws.regions.Region
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement
import com.amazonaws.services.simplesystemsmanagement.model._

trait AmazonSSMTestClient extends AWSSimpleSystemsManagement {
  override def listResourceDataSync(listResourceDataSyncRequest: ListResourceDataSyncRequest): ListResourceDataSyncResult = ???

  override def listComplianceSummaries(listComplianceSummariesRequest: ListComplianceSummariesRequest): ListComplianceSummariesResult = ???

  override def createAssociation(createAssociationRequest: CreateAssociationRequest): CreateAssociationResult = ???

  override def describeDocumentPermission(describeDocumentPermissionRequest: DescribeDocumentPermissionRequest): DescribeDocumentPermissionResult = ???

  override def registerDefaultPatchBaseline(registerDefaultPatchBaselineRequest: RegisterDefaultPatchBaselineRequest): RegisterDefaultPatchBaselineResult = ???

  override def getCachedResponseMetadata(request: AmazonWebServiceRequest): ResponseMetadata = ???

  override def listAssociationVersions(listAssociationVersionsRequest: ListAssociationVersionsRequest): ListAssociationVersionsResult = ???

  override def describeInstancePatchStatesForPatchGroup(describeInstancePatchStatesForPatchGroupRequest: DescribeInstancePatchStatesForPatchGroupRequest): DescribeInstancePatchStatesForPatchGroupResult = ???

  override def describeDocument(describeDocumentRequest: DescribeDocumentRequest): DescribeDocumentResult = ???

  override def registerTargetWithMaintenanceWindow(registerTargetWithMaintenanceWindowRequest: RegisterTargetWithMaintenanceWindowRequest): RegisterTargetWithMaintenanceWindowResult = ???

  override def getMaintenanceWindowExecutionTaskInvocation(getMaintenanceWindowExecutionTaskInvocationRequest: GetMaintenanceWindowExecutionTaskInvocationRequest): GetMaintenanceWindowExecutionTaskInvocationResult = ???

  override def deleteDocument(deleteDocumentRequest: DeleteDocumentRequest): DeleteDocumentResult = ???

  override def getParameter(getParameterRequest: GetParameterRequest): GetParameterResult = {
    new GetParameterResult().withParameter(new com.amazonaws.services.simplesystemsmanagement.model.Parameter().withName(getParameterRequest.getName).withValue("successful-ssm-value"))
  }

  override def describeMaintenanceWindowExecutions(describeMaintenanceWindowExecutionsRequest: DescribeMaintenanceWindowExecutionsRequest): DescribeMaintenanceWindowExecutionsResult = ???

  override def deleteParameter(deleteParameterRequest: DeleteParameterRequest): DeleteParameterResult = ???

  override def updateMaintenanceWindowTask(updateMaintenanceWindowTaskRequest: UpdateMaintenanceWindowTaskRequest): UpdateMaintenanceWindowTaskResult = ???

  override def updateAssociation(updateAssociationRequest: UpdateAssociationRequest): UpdateAssociationResult = ???

  override def deregisterTargetFromMaintenanceWindow(deregisterTargetFromMaintenanceWindowRequest: DeregisterTargetFromMaintenanceWindowRequest): DeregisterTargetFromMaintenanceWindowResult = ???

  override def sendAutomationSignal(sendAutomationSignalRequest: SendAutomationSignalRequest): SendAutomationSignalResult = ???

  override def deleteActivation(deleteActivationRequest: DeleteActivationRequest): DeleteActivationResult = ???

  override def describeInstanceInformation(describeInstanceInformationRequest: DescribeInstanceInformationRequest): DescribeInstanceInformationResult = ???

  override def createAssociationBatch(createAssociationBatchRequest: CreateAssociationBatchRequest): CreateAssociationBatchResult = ???

  override def describeInstancePatches(describeInstancePatchesRequest: DescribeInstancePatchesRequest): DescribeInstancePatchesResult = ???

  override def getPatchBaselineForPatchGroup(getPatchBaselineForPatchGroupRequest: GetPatchBaselineForPatchGroupRequest): GetPatchBaselineForPatchGroupResult = ???

  override def putParameter(putParameterRequest: PutParameterRequest): PutParameterResult = ???

  override def deregisterPatchBaselineForPatchGroup(deregisterPatchBaselineForPatchGroupRequest: DeregisterPatchBaselineForPatchGroupRequest): DeregisterPatchBaselineForPatchGroupResult = ???

  override def getParameterHistory(getParameterHistoryRequest: GetParameterHistoryRequest): GetParameterHistoryResult = ???

  override def describePatchGroups(describePatchGroupsRequest: DescribePatchGroupsRequest): DescribePatchGroupsResult = ???

  override def updateDocument(updateDocumentRequest: UpdateDocumentRequest): UpdateDocumentResult = ???

  override def getCommandInvocation(getCommandInvocationRequest: GetCommandInvocationRequest): GetCommandInvocationResult = ???

  override def deleteParameters(deleteParametersRequest: DeleteParametersRequest): DeleteParametersResult = ???

  override def describeAssociation(describeAssociationRequest: DescribeAssociationRequest): DescribeAssociationResult = ???

  override def getDeployablePatchSnapshotForInstance(getDeployablePatchSnapshotForInstanceRequest: GetDeployablePatchSnapshotForInstanceRequest): GetDeployablePatchSnapshotForInstanceResult = ???

  override def getMaintenanceWindow(getMaintenanceWindowRequest: GetMaintenanceWindowRequest): GetMaintenanceWindowResult = ???

  override def deleteMaintenanceWindow(deleteMaintenanceWindowRequest: DeleteMaintenanceWindowRequest): DeleteMaintenanceWindowResult = ???

  override def createActivation(createActivationRequest: CreateActivationRequest): CreateActivationResult = ???

  override def describeMaintenanceWindows(describeMaintenanceWindowsRequest: DescribeMaintenanceWindowsRequest): DescribeMaintenanceWindowsResult = ???

  override def setEndpoint(endpoint: String): Unit = ???

  override def registerPatchBaselineForPatchGroup(registerPatchBaselineForPatchGroupRequest: RegisterPatchBaselineForPatchGroupRequest): RegisterPatchBaselineForPatchGroupResult = ???

  override def sendCommand(sendCommandRequest: SendCommandRequest): SendCommandResult = ???

  override def describeEffectiveInstanceAssociations(describeEffectiveInstanceAssociationsRequest: DescribeEffectiveInstanceAssociationsRequest): DescribeEffectiveInstanceAssociationsResult = ???

  override def updateMaintenanceWindowTarget(updateMaintenanceWindowTargetRequest: UpdateMaintenanceWindowTargetRequest): UpdateMaintenanceWindowTargetResult = ???

  override def addTagsToResource(addTagsToResourceRequest: AddTagsToResourceRequest): AddTagsToResourceResult = ???

  override def getInventorySchema(getInventorySchemaRequest: GetInventorySchemaRequest): GetInventorySchemaResult = ???

  override def getInventory(getInventoryRequest: GetInventoryRequest): GetInventoryResult = ???

  override def deletePatchBaseline(deletePatchBaselineRequest: DeletePatchBaselineRequest): DeletePatchBaselineResult = ???

  override def describeParameters(describeParametersRequest: DescribeParametersRequest): DescribeParametersResult = ???

  override def getParametersByPath(getParametersByPathRequest: GetParametersByPathRequest): GetParametersByPathResult = ???

  override def createPatchBaseline(createPatchBaselineRequest: CreatePatchBaselineRequest): CreatePatchBaselineResult = ???

  override def setRegion(region: Region): Unit = ???

  override def createMaintenanceWindow(createMaintenanceWindowRequest: CreateMaintenanceWindowRequest): CreateMaintenanceWindowResult = ???

  override def listDocuments(listDocumentsRequest: ListDocumentsRequest): ListDocumentsResult = ???

  override def listDocuments(): ListDocumentsResult = ???

  override def createDocument(createDocumentRequest: CreateDocumentRequest): CreateDocumentResult = ???

  override def listAssociations(listAssociationsRequest: ListAssociationsRequest): ListAssociationsResult = ???

  override def deleteAssociation(deleteAssociationRequest: DeleteAssociationRequest): DeleteAssociationResult = ???

  override def getParameters(getParametersRequest: GetParametersRequest): GetParametersResult = ???

  override def describeAvailablePatches(describeAvailablePatchesRequest: DescribeAvailablePatchesRequest): DescribeAvailablePatchesResult = ???

  override def getMaintenanceWindowExecutionTask(getMaintenanceWindowExecutionTaskRequest: GetMaintenanceWindowExecutionTaskRequest): GetMaintenanceWindowExecutionTaskResult = ???

  override def describeMaintenanceWindowExecutionTaskInvocations(describeMaintenanceWindowExecutionTaskInvocationsRequest: DescribeMaintenanceWindowExecutionTaskInvocationsRequest): DescribeMaintenanceWindowExecutionTaskInvocationsResult = ???

  override def deleteResourceDataSync(deleteResourceDataSyncRequest: DeleteResourceDataSyncRequest): DeleteResourceDataSyncResult = ???

  override def registerTaskWithMaintenanceWindow(registerTaskWithMaintenanceWindowRequest: RegisterTaskWithMaintenanceWindowRequest): RegisterTaskWithMaintenanceWindowResult = ???

  override def describePatchGroupState(describePatchGroupStateRequest: DescribePatchGroupStateRequest): DescribePatchGroupStateResult = ???

  override def putInventory(putInventoryRequest: PutInventoryRequest): PutInventoryResult = ???

  override def getMaintenanceWindowExecution(getMaintenanceWindowExecutionRequest: GetMaintenanceWindowExecutionRequest): GetMaintenanceWindowExecutionResult = ???

  override def deregisterTaskFromMaintenanceWindow(deregisterTaskFromMaintenanceWindowRequest: DeregisterTaskFromMaintenanceWindowRequest): DeregisterTaskFromMaintenanceWindowResult = ???

  override def describeMaintenanceWindowExecutionTasks(describeMaintenanceWindowExecutionTasksRequest: DescribeMaintenanceWindowExecutionTasksRequest): DescribeMaintenanceWindowExecutionTasksResult = ???

  override def describeActivations(describeActivationsRequest: DescribeActivationsRequest): DescribeActivationsResult = ???

  override def stopAutomationExecution(stopAutomationExecutionRequest: StopAutomationExecutionRequest): StopAutomationExecutionResult = ???

  override def getDocument(getDocumentRequest: GetDocumentRequest): GetDocumentResult = ???

  override def listTagsForResource(listTagsForResourceRequest: ListTagsForResourceRequest): ListTagsForResourceResult = ???

  override def createResourceDataSync(createResourceDataSyncRequest: CreateResourceDataSyncRequest): CreateResourceDataSyncResult = ???

  override def getDefaultPatchBaseline(getDefaultPatchBaselineRequest: GetDefaultPatchBaselineRequest): GetDefaultPatchBaselineResult = ???

  override def describeMaintenanceWindowTasks(describeMaintenanceWindowTasksRequest: DescribeMaintenanceWindowTasksRequest): DescribeMaintenanceWindowTasksResult = ???

  override def startAutomationExecution(startAutomationExecutionRequest: StartAutomationExecutionRequest): StartAutomationExecutionResult = ???

  override def updateAssociationStatus(updateAssociationStatusRequest: UpdateAssociationStatusRequest): UpdateAssociationStatusResult = ???

  override def deregisterManagedInstance(deregisterManagedInstanceRequest: DeregisterManagedInstanceRequest): DeregisterManagedInstanceResult = ???

  override def shutdown(): Unit = ???

  override def listDocumentVersions(listDocumentVersionsRequest: ListDocumentVersionsRequest): ListDocumentVersionsResult = ???

  override def removeTagsFromResource(removeTagsFromResourceRequest: RemoveTagsFromResourceRequest): RemoveTagsFromResourceResult = ???

  override def describePatchBaselines(describePatchBaselinesRequest: DescribePatchBaselinesRequest): DescribePatchBaselinesResult = ???

  override def describeAutomationExecutions(describeAutomationExecutionsRequest: DescribeAutomationExecutionsRequest): DescribeAutomationExecutionsResult = ???

  override def getMaintenanceWindowTask(getMaintenanceWindowTaskRequest: GetMaintenanceWindowTaskRequest): GetMaintenanceWindowTaskResult = ???

  override def describeAutomationStepExecutions(describeAutomationStepExecutionsRequest: DescribeAutomationStepExecutionsRequest): DescribeAutomationStepExecutionsResult = ???

  override def describeInstancePatchStates(describeInstancePatchStatesRequest: DescribeInstancePatchStatesRequest): DescribeInstancePatchStatesResult = ???

  override def updateDocumentDefaultVersion(updateDocumentDefaultVersionRequest: UpdateDocumentDefaultVersionRequest): UpdateDocumentDefaultVersionResult = ???

  override def listInventoryEntries(listInventoryEntriesRequest: ListInventoryEntriesRequest): ListInventoryEntriesResult = ???

  override def getPatchBaseline(getPatchBaselineRequest: GetPatchBaselineRequest): GetPatchBaselineResult = ???

  override def putComplianceItems(putComplianceItemsRequest: PutComplianceItemsRequest): PutComplianceItemsResult = ???

  override def describeInstanceAssociationsStatus(describeInstanceAssociationsStatusRequest: DescribeInstanceAssociationsStatusRequest): DescribeInstanceAssociationsStatusResult = ???

  override def describeEffectivePatchesForPatchBaseline(describeEffectivePatchesForPatchBaselineRequest: DescribeEffectivePatchesForPatchBaselineRequest): DescribeEffectivePatchesForPatchBaselineResult = ???

  override def updateMaintenanceWindow(updateMaintenanceWindowRequest: UpdateMaintenanceWindowRequest): UpdateMaintenanceWindowResult = ???

  override def describeMaintenanceWindowTargets(describeMaintenanceWindowTargetsRequest: DescribeMaintenanceWindowTargetsRequest): DescribeMaintenanceWindowTargetsResult = ???

  override def updateManagedInstanceRole(updateManagedInstanceRoleRequest: UpdateManagedInstanceRoleRequest): UpdateManagedInstanceRoleResult = ???

  override def listCommands(listCommandsRequest: ListCommandsRequest): ListCommandsResult = ???

  override def modifyDocumentPermission(modifyDocumentPermissionRequest: ModifyDocumentPermissionRequest): ModifyDocumentPermissionResult = ???

  override def listCommandInvocations(listCommandInvocationsRequest: ListCommandInvocationsRequest): ListCommandInvocationsResult = ???

  override def cancelCommand(cancelCommandRequest: CancelCommandRequest): CancelCommandResult = ???

  override def listComplianceItems(listComplianceItemsRequest: ListComplianceItemsRequest): ListComplianceItemsResult = ???

  override def updatePatchBaseline(updatePatchBaselineRequest: UpdatePatchBaselineRequest): UpdatePatchBaselineResult = ???

  override def listResourceComplianceSummaries(listResourceComplianceSummariesRequest: ListResourceComplianceSummariesRequest): ListResourceComplianceSummariesResult = ???

  override def getAutomationExecution(getAutomationExecutionRequest: GetAutomationExecutionRequest): GetAutomationExecutionResult = ???
}