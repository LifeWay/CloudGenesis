import unittest
import sns_dlq_error
import json
import os

class SNSTest(unittest.TestCase):

    def test_basic_error(self):
        jsEvent = json.load(open('./test-files/dlq-error.json'))
        jsNested = json.load(open('./test-files/nestedTest1.json'))
        message = sns_dlq_error.build_slack_message(jsNested, jsEvent)
        assert message['username'] == 'CloudFormation-GitOps'
        assert len(message['attachments']) == 2

    def test_error_with_two_templates(self):
        jsEvent = json.load(open('./test-files/dlq-error.json'))
        nestedS3Events = json.load(open('./test-files/twoTemplates.json'))
        message = sns_dlq_error.build_slack_message(nestedS3Events, jsEvent)
        assert message['username'] == 'CloudFormation-GitOps'
        assert len(message['attachments']) == 3

if __name__ == '__main__':
    unittest.main()