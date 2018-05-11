#!/usr/bin/env bash

docker build -f Dockerfile-MakeImage -t cfgitops:latest .
docker run \
  -v `pwd`:/gitops \
  -v ~/.sbt:/root/.sbt \
  -v ~/.ivy2:/root/.ivy2 \
  -w /gitops \
  cfgitops:latest ./docker-make.sh
