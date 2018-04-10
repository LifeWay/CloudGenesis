#!/usr/bin/env bash

#TODO: execute build within docker container, all source gets piped in, the output is all of the built resources, ready for a SAM package command.
# Docker container will need AWS creds passed in and the S3 bucket that it will upload packaged artifacts to.

(cd s3-event-handlers/; sbt universal:packageBin; cd -

#TODO: add the build steps for the JS lambdas
