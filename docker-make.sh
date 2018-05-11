#!/usr/bin/env bash
set -e

(cd s3-event-demux/; sbt universal:packageBin; cd -)
(cd s3-event-handlers/; sbt universal:packageBin; cd -)

(cd cf-notify/; zip cf-notify.zip lambda_notify.py; cd -)

export WEBHOOK=testHook CHANNEL=testChannel
(cd sns-notify/; python sns_test.py; cd -)
unset WEBHOOK CHANNEL
(cd sns-notify/; zip sns-error.zip sns_error.py slack.py; cd -)

(cd codebuild-notify/; yarn install; cd -)
