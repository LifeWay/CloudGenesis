

## Package Instructions
```aws cloudformation package --template template.yaml --s3-prefix cloudops/s3-event --s3-bucket test-sam-codestore --output-template-file packaged.yaml```
```aws cloudformation deploy --template-file packaged.yaml --stack-name cloudops-cf-automation-poc-with-iam --capabilities CAPABILITY_IAM --parameter-overrides AllowIAMCapabilities=true AccountIDArns="arn:aws:iam::567419588983:root" ExternalNotificationSNSArn="arn:aws:sns:us-east-1:463625765753:rm-cross-acct-test"```
