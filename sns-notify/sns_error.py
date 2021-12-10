import json
import urllib
import urllib.request
import slack

def error_lambda_handler(event, context):
    for record in event['Records']:
        msg = build_message(record['Sns']['Message'])
        send_to_slack(msg)

def build_message(msg):
    text = '❌ {msg}'.format(msg=msg)

    return {
        'icon_emoji': ':cloud:',
        'username': 'CloudGenesis',
        'channel': slack.CHANNEL,
        'blocks': [{
            'type': 'section',
            'text': {
                'type': 'mrkdwn',
                'text': text
            }
        }]
    }

def send_to_slack(message):
    data = json.dumps(message).encode("utf-8")
    req = urllib.request.Request(slack.WEBHOOK, data, {'Content-Type': 'application/json'})
    urllib.request.urlopen(req)


