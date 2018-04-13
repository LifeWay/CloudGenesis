package com.lifeway.cloudops.cloudformation

import java.io.{ByteArrayInputStream, IOException, InputStream}

import com.amazonaws.SdkClientException
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.s3.event.S3EventNotification
import com.amazonaws.services.s3.event.S3EventNotification.{
  S3BucketEntity,
  S3Entity,
  S3EventNotificationRecord,
  S3ObjectEntity
}
import com.amazonaws.services.s3.model._
import com.amazonaws.services.sns.model.PublishResult
import org.scalactic.{Bad, Good, Many, Or}
import utest._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success}

object EventProcessorTests extends TestSuite {
  val tests = Tests {
    'getS3ContentAsString - {
      val createFile: S3File = S3File("some-bucket", "stacks/test.123/file.yaml", "some-version-id", CreateUpdateEvent)
      val deleteFile: S3File = createFile.copy(eventType = DeletedEvent)
      val s3Object: S3Object = {
        val obj = new S3Object()
        obj.setObjectContent(new ByteArrayInputStream("file-contents-string".getBytes))
        obj
      }

      'returnSuccessForCreateUpdateEvent - {
        val s3Client = new AmazonS3TestClient {
          override def getObject(req: GetObjectRequest): S3Object =
            if (req.getBucketName == createFile.bucket && req.getKey == createFile.key && req.getVersionId == createFile.versionId)
              s3Object
            else throw new IllegalArgumentException
        }

        val res = EventProcessor.getS3ContentAsString(s3Client)(createFile)
        assert(res == Success("file-contents-string"))
      }

      'returnSuccessForDeleteEvent - {
        val s3Client = new AmazonS3TestClient {
          override def listVersions(req: ListVersionsRequest): VersionListing =
            if (req.getBucketName == deleteFile.bucket && req.getKeyMarker == deleteFile.key && req.getVersionIdMarker == deleteFile.versionId && req.getMaxResults == 1) {
              val sum           = new VersionListing()
              val summaryBadKey = new S3VersionSummary
              summaryBadKey.setKey("badKey.yaml")
              summaryBadKey.setVersionId("something-else")
              val summary1 = new S3VersionSummary()
              summary1.setKey(deleteFile.key)
              summary1.setVersionId("old-version-id")
              val summary2 = new S3VersionSummary()
              summary2.setKey(deleteFile.key)
              summary2.setVersionId("even-older-version-id")
              sum.setVersionSummaries(Seq(summaryBadKey, summary1, summary2).asJava)
              sum
            } else throw new IllegalArgumentException

          override def getObject(req: GetObjectRequest): S3Object =
            if (req.getBucketName == deleteFile.bucket && req.getKey == deleteFile.key && req.getVersionId == "old-version-id")
              s3Object
            else throw new IllegalArgumentException
        }

        val res = EventProcessor.getS3ContentAsString(s3Client)(deleteFile)
        assert(res == Success("file-contents-string"))
      }

      'returnFailureIfListVersionsFails - {
        val s3Client = new AmazonS3TestClient {
          override def listVersions(req: ListVersionsRequest): VersionListing =
            throw new Exception("list versions failed!")

          override def getObject(req: GetObjectRequest): S3Object =
            if (req.getBucketName == deleteFile.bucket && req.getKey == deleteFile.key && req.getVersionId == "old-version-id")
              s3Object
            else throw new IllegalArgumentException
        }

        val res = EventProcessor.getS3ContentAsString(s3Client)(deleteFile)
        assert(res.failed.get.getMessage == "list versions failed!")
      }

      'returnFailureIfGetObjectFails - {
        val s3Client = new AmazonS3TestClient {
          override def getObject(req: GetObjectRequest): S3Object =
            throw new Exception("get object failed!")
        }

        val res = EventProcessor.getS3ContentAsString(s3Client)(createFile)
        assert(res.failed.get.getMessage == "get object failed!")
      }

      'returnFailureIfObjectContentStreamFails - {
        val s3Fail: S3Object = {
          val obj = new S3Object()
          obj.setObjectContent(new InputStream() {
            override def read(): Int = throw new IOException("stream fail!")
          })
          obj
        }

        val s3Client = new AmazonS3TestClient {
          override def getObject(req: GetObjectRequest): S3Object = s3Fail
        }

        val res     = EventProcessor.getS3ContentAsString(s3Client)(createFile)
        val failure = res.failed.get
        assert(failure.isInstanceOf[SdkClientException])
        assert(res.failed.get.getMessage == "Error streaming content from S3 during download")
      }
    }

    'clientLoader - {
      'returnValidClients - {
        val s3File  = S3File("some-bucket", "stacks/test.123/file.yaml", "some-version-id", CreateUpdateEvent)
        val s3File2 = S3File("some-bucket", "stacks/56798/file2.yaml", "some-version-id2", CreateUpdateEvent)
        val events  = Seq(s3File, s3File2)
        val result  = EventProcessor.clientLoader("some-deployer-role", null)(events)

        assert(result.keySet.contains("123"))
        assert(result.keySet.contains("56798"))
      }
    }

    'handleStack - {
      val sampleStackYaml =
        """
          |StackName: my-stack-name
          |Template: demo/demo-role.yaml
          |Tags:
          | - Key: Thing
          |   Value: appA
          | - Key: Owner
          |   Value: ProductOwner
          | - Key: Environment
          |   Value: dev
          |Parameters:
          | - Name: Environment
          |   Value: dev
        """.stripMargin.trim

      'returnSuccess - {
        val s3File = S3File("some-bucket", "stacks/test.123/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executor = new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("my-stack-name")) Good(())
            else throw new IllegalArgumentException
        }
        val result =
          EventProcessor.handleStack(_ => Success(sampleStackYaml), null, None, false)(null, s3File, executor)
        assert(result == Good(()))
      }

      'returnFailureIfS3ContentFails - {
        val s3File = S3File("some-bucket", "stacks/test.123/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executor = new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("productx-stackz")) Good(())
            else throw new IllegalArgumentException
        }
        val result =
          EventProcessor.handleStack(_ => Failure(new Exception("S3 Fail")), null, None, false)(null, s3File, executor)
        assert(result.isBad)
        assert(
          result.swap.get == StackConfigError(
            "Failed to retrieve or parse stack file for: stacks/test.123/productx/stackz.yaml. Details: S3 Fail"))
      }

      'returnFailureIfConfigFailsParsing - {
        val s3File = S3File("some-bucket", "stacks/test.123/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executor = new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("productx-stackz")) Good(())
            else throw new IllegalArgumentException
        }
        val result =
          EventProcessor.handleStack(_ => Success("%NotYaml!"), null, None, false)(null, s3File, executor)
        assert(result.isBad)
        assert(
          result.swap.get
            .asInstanceOf[StackConfigError]
            .msg
            .contains("expected alphabetic or numeric character, but found !"))
      }

      'returnFailureIfConfigFailsDecoding - {
        val missingTemplateYaml =
          """
            |Parameters:
            | - Name: Environment
            |   Value: dev
          """.stripMargin.trim

        val s3File = S3File("some-bucket", "stacks/test.123/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executor = new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("productx-stackz")) Good(())
            else throw new IllegalArgumentException
        }
        val result =
          EventProcessor.handleStack(_ => Success(missingTemplateYaml), null, None, false)(null, s3File, executor)
        assert(result.isBad)
        assert(
          result.swap.get
            .asInstanceOf[StackConfigError]
            .msg
            .contains("value on failed cursor: DownField(Template)"))
      }

      'forceSemanticNamingIfEnabled - {
        val s3File = S3File("some-bucket", "stacks/test.123/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executor = new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("productx-stackz")) Good(())
            else throw new IllegalArgumentException
        }
        val result =
          EventProcessor.handleStack(_ => Success(sampleStackYaml), null, None, semanticStackNaming = true)(null,
                                                                                                            s3File,
                                                                                                            executor)
        assert(result == Good(()))
      }

      'useSemanticNamingIfForceDisabledAndStackNameMissing - {
        val missingStackName =
          """
            |Template: demo/demo-role.yaml
            |Tags:
            | - Key: Thing
            |   Value: appA
            | - Key: Owner
            |   Value: ProductOwner
            | - Key: Environment
            |   Value: dev
            |Parameters:
            | - Name: Environment
            |   Value: dev
          """.stripMargin.trim

        val s3File = S3File("some-bucket", "stacks/test.123/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executor = new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("productx-stackz")) Good(())
            else throw new IllegalArgumentException
        }
        val result =
          EventProcessor.handleStack(_ => Success(missingStackName), null, None, semanticStackNaming = false)(null,
                                                                                                              s3File,
                                                                                                              executor)
        assert(result == Good(()))
      }

      'sendExternalNotificationsIfEnabled - {
        val s3File = S3File("some-bucket", "stacks/test.123/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executor = new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("my-stack-name")) Good(())
            else throw new IllegalArgumentException
        }
        val snsClient = new AmazonSNSTestClient {
          override def publish(topicArn: String, message: String, subject: String): PublishResult =
            if (topicArn.equals("external-sns-arm"))
              new PublishResult()
            else throw new IllegalArgumentException
        }

        val result =
          EventProcessor.handleStack(_ => Success(sampleStackYaml), snsClient, Some("external-sns-arm"), false)(
            null,
            s3File,
            executor)
        assert(result == Good(()))
      }

      'errorOnExternalNotificationFailing - {
        val s3File = S3File("some-bucket", "stacks/test.123/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executor = new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("my-stack-name")) Good(())
            else throw new IllegalArgumentException
        }
        val snsClient = new AmazonSNSTestClient {
          override def publish(topicArn: String, message: String, subject: String): PublishResult =
            throw new Exception("SNS explosion")
        }

        val result =
          EventProcessor.handleStack(_ => Success(sampleStackYaml), snsClient, Some("external-sns-arm"), false)(
            null,
            s3File,
            executor)
        assert(result.isBad)
        assert(
          result.swap.get
            .asInstanceOf[StackError]
            .msg
            .contains("Stack operation succeeded, but external notification failed for"))
      }
    }

    'processEvent - {
      def eventNotificationRecordBuilder(bucketName: String,
                                         fileName: String,
                                         versionId: String): S3EventNotificationRecord = {
        val s3Entity = new S3Entity(null,
                                    new S3BucketEntity(bucketName, null, null),
                                    new S3ObjectEntity(fileName, null, null, versionId, null),
                                    null)
        new S3EventNotificationRecord(null, null, null, null, null, null, null, s3Entity, null)
      }

      'returnSuccess - {
        val record1   = eventNotificationRecordBuilder("some-bucket-name", "stacks/test.123/file.yaml", "my-version-id")
        val record2   = eventNotificationRecordBuilder("some-bucket-name", "stacks/56798/file2.yaml", "my-version-id2")
        val event     = new S3EventNotification(Seq(record1, record2).asJava)
        val eventType = CreateUpdateEvent

        val request = EventProcessor.processEvent((_, _, _) => Good(()),
                                                  _ => Map("123" -> null, "56798" -> null),
                                                  Map(CreateUpdateEvent -> null))(event, eventType)
        assert(request == Good(()))
      }

      'accumulateErrors - {
        val record1   = eventNotificationRecordBuilder("some-bucket-name", "stacks/test.123/file.yaml", "my-version-id")
        val record2   = eventNotificationRecordBuilder("some-bucket-name", "stacks/56798/file1.yaml", "my-version-id2")
        val record3   = eventNotificationRecordBuilder("some-bucket-name", "stacks/56798/file2.yaml", "my-version-id3")
        val event     = new S3EventNotification(Seq(record1, record2, record3).asJava)
        val eventType = CreateUpdateEvent

        def makeErrorsHandler(c: AmazonCloudFormation, f: S3File, e: StackExecutor): Unit Or AutomationError =
          f.key match {
            case "stacks/test.123/file.yaml" => Bad(StackError("stack go boom"))
            case "stacks/56798/file2.yaml"   => Bad(StackError("bad stack parameters!"))
            case _                           => Good(())
          }

        val request = EventProcessor.processEvent(makeErrorsHandler,
                                                  _ => Map("123" -> null, "56798" -> null),
                                                  Map(CreateUpdateEvent -> null))(event, eventType)
        assert(request.swap.get == Many(StackError("stack go boom"), StackError("bad stack parameters!")))
      }
    }

    'accountNumberParser - {
      'parseAccountNumberWithAliasName - {
        val number = EventProcessor.accountNumberParser("stacks/accountAlias.123456789")
        assert(number == "123456789")
      }

      'parseAccountNumberWithDeepFileName - {
        val number = EventProcessor.accountNumberParser("stacks/accountAlias.123456789/something/very/deep.yaml")
        assert(number == "123456789")
      }

      'parseAccountNumberWithoutAliasName - {
        val number = EventProcessor.accountNumberParser("stacks/123456789")
        assert(number == "123456789")
      }

      'parseAccountNumberWithoutAliasNameWithDeepFileName - {
        val number = EventProcessor.accountNumberParser("stacks/123456789/something/very/deep.yaml")
        assert(number == "123456789")
      }
    }
  }
}
