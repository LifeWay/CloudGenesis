import json
import urllib.request
import slack
import os
import unittest

def dlq_lambda_handler(event, context):
    for record in event['Records']:
        messageRecords = json.loads(record['Sns']['Message'])

        for rec in messageRecords['Records']:
            msg = build_slack_message(rec, event)
            send_to_slack(msg)

def build_slack_message(record, event):
    #iterate through nested messages
    topMessage = json.loads(record['Sns']['Message'])
    nestedMessages = topMessage['Records']
    blocks = []

    for message in nestedMessages:
        blocks.append(build_block(message))

    return build_message(blocks, event)

def send_to_slack(message):
    data = json.dumps(message).encode("utf-8")
    req = urllib.request.Request(slack.WEBHOOK, data, {'Content-Type': 'application/json'})
    urllib.request.urlopen(req)


def build_message(blocks, event):
    blocks.append(build_custom_error_message_block(event))

    message = {
        'icon_emoji': ':cloud:',
        'username': 'CloudGenesis',
        'channel': slack.CHANNEL,
        'blocks': blocks
    }

    return message

def build_block(rec):
    event_name = rec['eventName']
    bucket = rec['s3']['bucket']['name']
    key = rec['s3']['object']['key']
    text = '❌ Stack Error at: {object}'.format(object=bucket+key)

    return {
        'type': 'section',
        'text': {
            'type': 'mrkdwn',
            'text': text
        }
    }

def build_custom_error_message_block(rec):
    try:
        title = rec['Records'][0]['Sns']['MessageAttributes']['ErrorMessage']['Value']
    except KeyError:
        title = "Stack Error"

    text = '❌ {title}'.format(title=title)

    return {
        'type': 'section',
        'text': {
            'type': 'mrkdwn',
            'text': text
        }
    }
