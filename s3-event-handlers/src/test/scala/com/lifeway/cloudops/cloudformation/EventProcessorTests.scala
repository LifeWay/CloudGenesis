package com.lifeway.cloudops.cloudformation

import java.io.{ByteArrayInputStream, IOException, InputStream}

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.{AmazonServiceException, SdkClientException}
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.s3.model._
import com.amazonaws.services.sns.model.PublishResult
import org.scalactic.{Bad, Good, Or}
import utest._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

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

    'cfClientBuilder - {
      'givenAWSCredsAndRegionReturnBuilderWithRegion - {
        val creds   = new AWSStaticCredentialsProvider(new BasicAWSCredentials("some-key-id", "some-secret"))
        val builder = EventProcessor.cfClientBuilder(creds, "us-west-2")
        assert(builder.getCredentials.getCredentials.getAWSAccessKeyId == "some-key-id")
        assert(builder.getCredentials.getCredentials.getAWSSecretKey == "some-secret")
        assert(builder.getRegion == "us-west-2")
      }
    }

    'clientLoader - {
      'returnValidClient - {
        val s3File = S3File("some-bucket", "stacks/test.123/us-east-1/file.yaml", "some-version-id", CreateUpdateEvent)
        val result = EventProcessor.clientLoader()("some-deployer-role", null)(s3File)

        assert(result != null)
      }
    }

    'checkFileExists - {
      'returnGoodTrueIfFileExists - {
        val s3Client = new AmazonS3TestClient {
          override def doesObjectExist(bucketName: String, objectName: String): Boolean = true
        }

        val result = EventProcessor.checkFileExists(s3Client)("abc", "thing/x.yaml")
        assert(result == Good(true))
      }

      'returnGoodFalseIfFileMissing - {
        val s3Client = new AmazonS3TestClient {
          override def doesObjectExist(bucketName: String, objectName: String): Boolean = false
        }

        val result = EventProcessor.checkFileExists(s3Client)("abc", "thing/x.yaml")
        assert(result == Good(false))
      }

      'returnFailureIfAWSIsDown - {
        val s3Client = new AmazonS3TestClient {
          override def doesObjectExist(bucketName: String, objectName: String): Boolean = {
            val ex = new AmazonServiceException("boom")
            ex.setStatusCode(500)
            throw ex
          }
        }

        val result = EventProcessor.checkFileExists(s3Client)("abc", "thing/x.yaml")
        assert(result.isBad)
        assert(result.swap.get == ServiceError(
          "AWS 500 Service Exception: Failed to check for existence of s3 file: thing/x.yaml. Reason: boom (Service: null; Status Code: 500; Error Code: null; Request ID: null)"))
      }

      'returnStackErrorForOtherAWSErrors - {
        val s3Client = new AmazonS3TestClient {
          override def doesObjectExist(bucketName: String, objectName: String): Boolean = throw new Exception("boom")
        }

        val result = EventProcessor.checkFileExists(s3Client)("abc", "thing/x.yaml")
        assert(result.isBad)
        assert(result.swap.get == StackError("Failed to check for existence of s3 file: thing/x.yaml. Reason: boom"))
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
        val s3File =
          S3File("some-bucket", "stacks/test.123/us-east-1/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executors: Map[EventType, StackExecutor] = Map(CreateUpdateEvent -> new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("my-stack-name")) Good(())
            else throw new IllegalArgumentException
        })

        val result =
          EventProcessor.handleStack(_ => Success(sampleStackYaml),
                                     (_, _) => Good(true),
                                     _ => null,
                                     null,
                                     None,
                                     false,
                                     executors)(s3File)
        assert(result == Good(()))
      }

      'useTheCorrectStackExecutorForType - {
        val s3File =
          S3File("some-bucket", "stacks/test.123/us-east-1/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executors: Map[EventType, StackExecutor] = Map(
          CreateUpdateEvent -> new StackExecutor {
            override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] =
              (_, _, _) => Bad(StackError("The CreateUpdate executor!"))
          },
          DeletedEvent -> new StackExecutor {
            override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] =
              (_, _, _) => Bad(StackConfigError("The Delete Executor!"))
          }
        )

        val createExecutor =
          EventProcessor.handleStack(_ => Success(sampleStackYaml),
                                     (_, _) => Good(true),
                                     _ => null,
                                     null,
                                     None,
                                     false,
                                     executors)(s3File)
        assert(createExecutor == Bad(StackError("The CreateUpdate executor!")))

        val updateExecutor =
          EventProcessor.handleStack(_ => Success(sampleStackYaml),
                                     (_, _) => Good(true),
                                     _ => null,
                                     null,
                                     None,
                                     false,
                                     executors)(s3File.copy(eventType = DeletedEvent))
        assert(updateExecutor == Bad(StackConfigError("The Delete Executor!")))
      }

      'dontCallS3FileExistsOnDelete - {
        val s3File =
          S3File("some-bucket", "stacks/test.123/us-east-1/productx/stackz.yaml", "some-version-id", DeletedEvent)
        val executors: Map[EventType, StackExecutor] = Map(DeletedEvent -> new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("my-stack-name")) Good(())
            else throw new IllegalArgumentException
        })

        val result =
          EventProcessor.handleStack(
            _ => Success(sampleStackYaml),
            (_, _) => Good(false), //returns false which would fail the result if not DeletedEvent
            _ => null,
            null,
            None,
            false,
            executors)(s3File)
        assert(result == Good(()))
      }

      'returnFailureIfS3ContentFails - {
        val s3File =
          S3File("some-bucket", "stacks/test.123/us-east-1/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executors: Map[EventType, StackExecutor] = Map(CreateUpdateEvent -> new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("productx-stackz")) Good(())
            else throw new IllegalArgumentException
        })

        val result =
          EventProcessor.handleStack(_ => Failure(new Exception("S3 Fail")),
                                     (_, _) => Good(true),
                                     _ => null,
                                     null,
                                     None,
                                     false,
                                     executors)(s3File)
        assert(result.isBad)
        assert(result.swap.get == StackConfigError(
          "Failed to retrieve or parse stack file for: stacks/test.123/us-east-1/productx/stackz.yaml. Details: S3 Fail"))
      }

      'returnFailureIfTemplateIsNotInBucket - {
        val s3File =
          S3File("some-bucket", "stacks/test.123/us-east-1/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executors: Map[EventType, StackExecutor] = Map(CreateUpdateEvent -> new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("productx-stackz")) Good(())
            else throw new IllegalArgumentException
        })

        val result =
          EventProcessor.handleStack(_ => Success(sampleStackYaml),
                                     (_, _) => Good(false),
                                     _ => null,
                                     null,
                                     None,
                                     false,
                                     executors)(s3File)
        assert(result.isBad)
        assert(
          result.swap.get == StackConfigError(
            "Invalid template path: demo/demo-role.yaml does not exist in the templates directory."))
      }

      'returnFailureIfAWSIsDown - {
        val s3File =
          S3File("some-bucket", "stacks/test.123/us-east-1/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val s3Failure: S3File => Try[String] = _ =>
          Try {
            val ex = new AmazonServiceException("boom")
            ex.setStatusCode(500)
            throw ex
        }

        val result =
          EventProcessor.handleStack(s3Failure, (_, _) => Good(true), _ => null, null, None, false, null)(s3File)
        assert(result.isBad)
        assert(result.swap.get == ServiceError(
          "Failed to retrieve stack file: stacks/test.123/us-east-1/productx/stackz.yaml due to: boom (Service: null; Status Code: 500; Error Code: null; Request ID: null)"))
      }

      'returnFailureIfConfigFailsParsing - {
        val s3File =
          S3File("some-bucket", "stacks/test.123/us-east-1/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executors: Map[EventType, StackExecutor] = Map(CreateUpdateEvent -> new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("productx-stackz")) Good(())
            else throw new IllegalArgumentException
        })

        val result =
          EventProcessor.handleStack(_ => Success("%NotYaml!"),
                                     (_, _) => Good(true),
                                     _ => null,
                                     null,
                                     None,
                                     false,
                                     executors)(s3File)
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

        val s3File =
          S3File("some-bucket", "stacks/test.123/us-east-1/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executors: Map[EventType, StackExecutor] = Map(CreateUpdateEvent -> new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("productx-stackz")) Good(())
            else throw new IllegalArgumentException
        })
        val result =
          EventProcessor.handleStack(_ => Success(missingTemplateYaml),
                                     (_, _) => Good(true),
                                     _ => null,
                                     null,
                                     None,
                                     false,
                                     executors)(s3File)
        assert(result.isBad)
        assert(
          result.swap.get
            .asInstanceOf[StackConfigError]
            .msg
            .contains("value on failed cursor: DownField(Template)"))
      }

      'forceSemanticNamingIfEnabled - {
        val s3File =
          S3File("some-bucket", "stacks/test.123/us-east-1/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executors: Map[EventType, StackExecutor] = Map(CreateUpdateEvent -> new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("productx-stackz")) Good(())
            else throw new IllegalArgumentException
        })
        val result =
          EventProcessor.handleStack(_ => Success(sampleStackYaml),
                                     (_, _) => Good(true),
                                     _ => null,
                                     null,
                                     None,
                                     semanticStackNaming = true,
                                     executors)(s3File)
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

        val s3File =
          S3File("some-bucket", "stacks/test.123/us-east-1/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executors: Map[EventType, StackExecutor] = Map(CreateUpdateEvent -> new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("productx-stackz")) Good(())
            else throw new IllegalArgumentException
        })
        val result =
          EventProcessor.handleStack(_ => Success(missingStackName),
                                     (_, _) => Good(true),
                                     _ => null,
                                     null,
                                     None,
                                     semanticStackNaming = false,
                                     executors)(s3File)
        assert(result == Good(()))
      }

      'sendExternalNotificationsIfEnabled - {
        val s3File =
          S3File("some-bucket", "stacks/test.123/us-east-1/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executors: Map[EventType, StackExecutor] = Map(CreateUpdateEvent -> new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("my-stack-name")) Good(())
            else throw new IllegalArgumentException
        })
        val snsClient = new AmazonSNSTestClient {
          override def publish(topicArn: String, message: String, subject: String): PublishResult =
            if (topicArn.equals("external-sns-arm"))
              new PublishResult()
            else throw new IllegalArgumentException
        }

        val result =
          EventProcessor.handleStack(_ => Success(sampleStackYaml),
                                     (_, _) => Good(true),
                                     _ => null,
                                     snsClient,
                                     Some("external-sns-arm"),
                                     false,
                                     executors)(s3File)
        assert(result == Good(()))
      }

      'errorOnExternalNotificationFailing - {
        val s3File =
          S3File("some-bucket", "stacks/test.123/us-east-1/productx/stackz.yaml", "some-version-id", CreateUpdateEvent)
        val executors: Map[EventType, StackExecutor] = Map(CreateUpdateEvent -> new StackExecutor {
          override val execute: (AmazonCloudFormation, StackConfig, S3File) => Or[Unit, AutomationError] = (c, sc, f) =>
            if (sc.stackName.equals("my-stack-name")) Good(())
            else throw new IllegalArgumentException
        })
        val snsClient = new AmazonSNSTestClient {
          override def publish(topicArn: String, message: String, subject: String): PublishResult =
            throw new Exception("SNS explosion")
        }

        val result =
          EventProcessor.handleStack(_ => Success(sampleStackYaml),
                                     (_, _) => Good(true),
                                     _ => null,
                                     snsClient,
                                     Some("external-sns-arm"),
                                     false,
                                     executors)(s3File)
        assert(result.isBad)
        assert(
          result.swap.get
            .asInstanceOf[StackError]
            .msg
            .contains("Stack operation succeeded, but external notification failed for"))
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

    'regionIdParser - {
      'parseUsEast2 - {
        val name = EventProcessor.regionIdParser("stacks/12456789/us-east-2/something/very/deep.yaml")
        assert(name == "us-east-2")
      }
      'parseEuCentral1 - {
        val name = EventProcessor.regionIdParser("stacks/12456789/eu-central-1/something/very/deep.yaml")
        assert(name == "eu-central-1")
      }
    }
  }
}
