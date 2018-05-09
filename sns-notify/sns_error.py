import json
import urllib2
import slack

def error_lambda_handler(event, context):
    for record in event['Records']:
        msg = build_message(record['Sns']['Message'])
        send_to_slack(msg)

def build_message(msg):
    return {
        'icon_emoji': ':cloud:',
        'username': 'CloudFormation-GitOps',
        'channel': slack.CHANNEL,
        'attachments': [{
            'fallback': msg,
            'text': msg,
            'color': 'danger'
        }]
    }

def send_to_slack(message):
    data = json.dumps(message)
    req = urllib2.Request(slack.WEBHOOK, data, {'Content-Type': 'application/json'})
    urllib2.urlopen(req)


