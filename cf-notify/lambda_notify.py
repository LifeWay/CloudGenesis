import json
import shlex
import urllib.request
import re
import os

#
# Original inspiration for this came from the following Library (no OSS license applied at that time we forked it):
# From: https://github.com/guardian/cf-notify
#

# Mapping CloudFormation status codes to colors for Slack message blocks
# Status codes from http://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-describing-stacks.html
STATUS_COLORS = {
    'CREATE_COMPLETE': 'good',
    'CREATE_IN_PROGRESS': 'good',
    'CREATE_FAILED': 'danger',
    'DELETE_COMPLETE': 'good',
    'DELETE_FAILED': 'danger',
    'DELETE_IN_PROGRESS': 'good',
    'REVIEW_IN_PROGRESS': 'good',
    'ROLLBACK_COMPLETE': 'warning',
    'ROLLBACK_FAILED': 'danger',
    'ROLLBACK_IN_PROGRESS': 'warning',
    'UPDATE_COMPLETE': 'good',
    'UPDATE_COMPLETE_CLEANUP_IN_PROGRESS': 'good',
    'UPDATE_IN_PROGRESS': 'good',
    'UPDATE_ROLLBACK_COMPLETE': 'warning',
    'UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS': 'warning',
    'UPDATE_ROLLBACK_FAILED': 'danger',
    'UPDATE_ROLLBACK_IN_PROGRESS': 'warning',
    'DELETE_FAILED': 'danger'
}

# List of stack status events that will emit to slack
EVENTS_FOR_SLACK = [
    'CREATE_COMPLETE',
    'CREATE_IN_PROGRESS',
    'CREATE_FAILED',
    'DELETE_COMPLETE',
    'DELETE_IN_PROGRESS',
    'ROLLBACK_COMPLETE',
    'ROLLBACK_FAILED',
    'ROLLBACK_IN_PROGRESS',
    'UPDATE_COMPLETE',
    'UPDATE_IN_PROGRESS',
    'UPDATE_ROLLBACK_COMPLETE',
    'UPDATE_ROLLBACK_FAILED',
    'UPDATE_ROLLBACK_IN_PROGRESS',
    'REVIEW_IN_PROGRESS',
    'DELETE_FAILED'
]

ERROR_EVENTS = [
    'CREATE_FAILED',
    'ROLLBACK_FAILED',
    'UPDATE_ROLLBACK_FAILED',
    'DELETE_FAILED',
    'UPDATE_FAILED'
]


def lambda_handler(event, context):
    message = event['Records'][0]['Sns']
    sns_message = message['Message']
    webhook = os.environ['WEBHOOK']
    channel = os.environ['CHANNEL']

    cf_message = dict(token.split('=', 1) for token in shlex.split(sns_message))

    # ignore messages that do not pertain to the Stack as a whole, unless they are error events that give valuable info

    if not cf_message['ResourceType'] == 'AWS::CloudFormation::Stack' and cf_message['ResourceStatus'] not in ERROR_EVENTS:
        return

    # ignore events we don't care about.

    if cf_message['ResourceStatus'] not in EVENTS_FOR_SLACK:
        return

    message = get_stack_update_message(cf_message, channel)
    data = json.dumps(message).encode("utf-8")
    req = urllib.request.Request(webhook, data, {'Content-Type': 'application/json'})
    urllib.request.urlopen(req)


def get_stack_update_message(cf_message, channel):
    return {
        'icon_emoji': ':cloud:',
        'channel': channel,
        'username': 'CloudGenesis',
        'blocks': get_stack_update_blocks(cf_message)
    }

def get_stack_update_blocks(cf_message):
    title = 'Stack <{link}|{stack}> has entered status: {status}'.format(
        link=get_stack_url(cf_message['StackId']),
        stack=cf_message['StackName'],
        status=cf_message['ResourceStatus'])

    if cf_message['ResourceStatus'] in ERROR_EVENTS:
        if 'ResourceStatusReason' in cf_message:
            title = title + "\n" + cf_message['ResourceStatusReason']


    return [
        {
            'type': 'section',
            'text': {
                'type': 'mrkdwn',
                'text': title
            }
        },
        {
            'type': 'section',
            'text': {
                'type': 'mrkdwn',
                'text': cf_message['StackId'],
            }
        }
    ]

def get_stack_region(stack_id):
    regex = re.compile('arn:aws:cloudformation:(?P<region>[a-z]{2}-[a-z]{4,9}-[1-3]{1})')

    return regex.match(stack_id).group('region')

def get_stack_url(stack_id):
    region = get_stack_region(stack_id)

    query = {
        'stackId': stack_id
    }

    return ('https://{region}.console.aws.amazon.com/cloudformation/home?region={region}#/stack/detail?{query}'
            .format(region=region, query=urllib.urlencode(query)))
