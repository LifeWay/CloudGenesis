#!/usr/bin/env bash

#TODO: execute build within docker container, all source gets piped in, the output is all of the built resources, ready for a SAM package command.
# Docker container will need AWS creds passed in and the S3 bucket that it will upload packaged artifacts to.

(cd s3-event-handlers/; sbt universal:packageBin; cd -)

#TODO: add the build steps for the JS lambdas

(cd cf-notify/; zip cf-notify.zip lambda_notify.py slack.py; cd -)

export WEBHOOK=testHook CHANNEL=testChannel
(cd sns-notify/; python sns_test.py; cd -)
unset WEBHOOK CHANNEL
(cd sns-notify/; zip sns-error.zip sns_error.py slack.py; cd -)

(cd codebuild-notify/; yarn install; cd -)
