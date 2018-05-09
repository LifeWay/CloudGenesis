import json
import urllib2
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
    attachments = []
    for message in nestedMessages:
        attachments.append(build_attachment(message))

    return build_message(attachments, event)

def send_to_slack(message):
    data = json.dumps(message)
    req = urllib2.Request(slack.WEBHOOK, data, {'Content-Type': 'application/json'})
    urllib2.urlopen(req)
        

def build_message(attachments, event):
    attachments.append(build_custom_error_message_attachment(event))
    
    message = {
        'icon_emoji': ':cloud:',
        'username': 'CloudFormation-GitOps',
        'channel': slack.CHANNEL,
        'attachments': attachments
    }
    return message

def build_attachment(rec):
    event_name = rec['eventName']
    bucket = rec['s3']['bucket']['name']
    key = rec['s3']['object']['key']
    title = 'Stack Error at: {object}'.format(object=bucket+key)
    return {
        'fallback': title,
        'text': title,
        'color': 'danger'
    }

def build_custom_error_message_attachment(rec):
    try:
        title = rec['Records'][0]['Sns']['MessageAttributes']['ErrorMessage']['Value']
    except KeyError:
        return {'title': "Stack ERROR", 'color': 'danger'}
    
    return {
        'fallback': title,
        'text': title,
        'color': 'danger'
    }
