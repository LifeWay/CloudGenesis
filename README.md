
# Automated Deployer


STEPS TO DEPLOY:
1.  Deploy a StackSet for the master / admin deployer. Use the `cf-role-master.stackset.yaml` template on your master / governance account. You can do this all in one account, but best practice would be to isolate it and ensure strong governance around the controller account.

    StackSet:
    * `Name`: Pick a name that makes sense to you.
    * Parameters: see below
    * AccountIds: Every Account you want this deployer to be able to manage
    * Regions: Pick anything. This stack only deploys IAM resources which are global. Thus, you only need to worry about which account ID's to deploy to, the resources created in this stackset will be available in every region.
  
    Parameters: 
    * `CFAccessRoleName`: This is the name of the Role that will be created in each account the stack set is deployed to. This is a named role. You will provide this name to the deployer SAM stack as a parameter when you launch it so that it knows what Role it needs to assume in each account it is deploying stacks to.
    * `CFServiceRoleName`: This is the name of the CloudFormation Service Role that will be created in each account the stack set is deployed to. This is a named role. You will provide this name to the deployer SAM stack as a parameter when you launch it so that every stack the deployer launches in another account will be launched with this Service Role (this role gets attached to the Stack itself).
    * `DeployerAccountId`: This is the AccountID that the deployer is running in. This is typically your master or governance account.
    * `AutomationStackName`: This is the stack name you will give to the deployer SAM stack when you launch it. This is necessary as part of a semantic naming scheme so that the `CFAccessRole` created in each account is able to get access to the S3 bucket created by the deployer stack. A classic CloudFormation chicken-and-egg problem where the actual `ARN` doesn't exist yet, so a semantic naming scheme must be used so that the IAM access can be setup ahead of the resource being created.

2.  Run the `make.sh` script.

    In order to run the make script, you will need a few things installed: sbt, python 3, and yarn. (This SAM stack is polyglot in terms of the Lambdas).

3. Package the stack via the aws cli. `aws cloudformation package --template template.yaml  --output-template-file packaged.yaml --s3-prefix cf-deployer --s3-bucket CHANGEME`

4. Deploy the packaged template either via the CLI or via the AWS Console.

    Parameters:
    * `AccountIds` - A comma delimited list of account Id's you want to the deployer to be able to deploy to. **NOTE** this only gives the role assumed by the deployer acesss to do this, however, it will only work if you've first given cross account access to the account the deployer is running in on those account id's. This is handled in Step 1 with the master stackset.
    * `AllowIAMCapabilities` - Enables the passing of the IAM capabilities set to CloudFormation. If this is false, IAM resources cannot be created even if the deployer has full admin access to create any kind of resource. 
    * `CFAccessRoleName` - The name you provided in step1 above. This is the role the deployer uses in each account to access CloudFormation.
    * `CFServiceRoleName` - OPTIONAL: The name you provided in step1 above. This is the service role passed to CloudFormation by the `CFAccessRoleName` for actually creating the resources. If this value is not provided, no service role will be passed and the permissions granted by `CFAccessRoleName` must be wide enough to create the resources necessary by the stacks.
    * `EmailContact` - If Slack is down or messages fail to post to slack, this is the email address that will receive all of the events. **NOTE** An SNS subscription email will be sent upon stack launch, you MUST click the link in the email to subscribe to the data feed, else the messages won't be delievered.
    * `ExternalNotificationSNSArn` - OPTIONAL: If provided, this is an ARN to an SNS topic where the deployer will publish events to for when it operates on a stack (create / update or delete). This notification event is useful for audit logging and tracking changes.
    * `SemanticStackNaming` - If disabled, users can provide a `StackName` value in their template (semantic naming is always used if no value is present). However, this is highly discouraged as it can be trivially easy to overrite another stack on accident. Thus, this property should be enabled for best practice. When enabled, stacks are named semantically, regardless if a value for `StackName` is provided.
        * For Instance: a stack file defined as such `/stacks/myaccount-name.123456789/divisionxyz/productc/license-service.yaml` would have the stack name of `divisionxyz-productc-license-service`
    * `SlackChannel` - The name of the slack channel name that is receiving the notifications, e.g. `cloudformation-automation`
    * `SlackWebHookUrl` - The webhook URL that you must setup with Slack for this service that has access to the above channel.
    * `SSMPath` - The base SSM path that the deployer will gain access to read secret values from. e.g. `/cloudformation-automation/admin-deployer/` would result in the deployer being able to read any secret value under that path, for instance: `/cloudformation-automation/admin-deployer/team-x/secret-value` would now be readable by the deployer. 
