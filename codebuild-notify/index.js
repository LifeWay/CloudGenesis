"use strict";

const Slack = require("slack-node");

exports.handler = (event, context, callback) => {

  const webhookUri = process.env.WEBHOOK;
  const channel = process.env.CHANNEL;
  const slack = new Slack();
  slack.setWebhook(webhookUri);

    event.Records.forEach( record => {
      var codebuild = JSON.parse(record.Sns.Message);
      var jobId = codebuild.id;
    
      const buildUrl = `https://console.aws.amazon.com/codebuild/home?region=${codebuild.region}#/builds`;
      slack.webhook(
        {
          channel: channel,
          username: 'GitFormation',
          icon_emoji: ':cloud:',
          text: `*Linters and Git Sync to S3* (${jobId}) is ${codebuild.detail[
            "build-status"
          ]} <${buildUrl}|Details>`
        },
        function(err, response) {
          return callback(null, response);
        }
      );

  });
  };