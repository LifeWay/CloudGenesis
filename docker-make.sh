#!/usr/bin/env bash
set -e

export AWS_REGION=us-east-1

(cd s3-event-demux/; sbt cleanFiles clean compile test universal:packageBin; cd -)
(cd s3-event-handlers/; sbt cleanFiles clean compile test universal:packageBin; cd -)

(cd cf-notify/; zip cf-notify.zip lambda_notify.py; cd -)

export WEBHOOK=testHook CHANNEL=testChannel
(cd sns-notify/; python3 sns_test.py; cd -)
unset WEBHOOK CHANNEL
(cd sns-notify/; zip sns-error.zip sns_error.py slack.py; cd -)

(cd codebuild-notify/; yarn install; cd -)
