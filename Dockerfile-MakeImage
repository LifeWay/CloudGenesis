FROM amazonlinux:latest

MAINTAINER Ryan Means <ryan.means@lifeway.com>

RUN yum -y update && yum -y install zip

# JAVA & SBT
RUN yum -y install java-1.8.0 && \
    curl https://bintray.com/sbt/rpm/rpm | tee /etc/yum.repos.d/bintray-sbt-rpm.repo && \
    yum -y install sbt

# PYTHON
RUN yum -y install python python-virtualenv python-pip

# NODE & YARN
RUN rpm --import https://rpm.nodesource.com/pub/el/NODESOURCE-GPG-SIGNING-KEY-EL && \
    rpm --import https://dl.yarnpkg.com/rpm/pubkey.gpg && \
    curl --silent --location https://dl.yarnpkg.com/rpm/yarn.repo | tee /etc/yum.repos.d/yarn.repo && \
    curl --silent --location https://rpm.nodesource.com/setup_6.x | bash - && \
    yum -y install yarn

RUN yum clean all
