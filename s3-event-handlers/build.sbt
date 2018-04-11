import NativePackagerHelper._

name := "s3-event-handlers"

version := "1.0"

scalaVersion := "2.12.4"

val awsVersion       = "1.11.303"

libraryDependencies ++= Seq(
  "com.amazonaws"            % "aws-lambda-java-core"        % "1.2.0",
  "com.amazonaws"            % "aws-lambda-java-events"      % "2.1.0",
  "com.amazonaws"            % "aws-java-sdk-s3"             % awsVersion,
  "com.amazonaws"            % "aws-java-sdk-cloudformation" % awsVersion,
  "com.amazonaws"            % "aws-java-sdk-sts"            % awsVersion,
  "com.amazonaws"            % "aws-java-sdk-sns"            % awsVersion,
  "org.scalactic"            %% "scalactic"                  % "3.0.5",
  "com.typesafe.akka"        %% "akka-actor"                 % "2.5.11",
  "io.circe"                 %% "circe-yaml"                 % "0.6.1",
  "org.apache.logging.log4j" % "log4j-core"                  % "2.8.2",
  "org.apache.logging.log4j" % "log4j-api"                   % "2.8.2",
  "org.apache.logging.log4j" % "log4j-slf4j-impl"            % "2.8.2",
  "com.lihaoyi"              %% "utest"                      % "0.6.4" % "test"
)

testFrameworks += new TestFramework("utest.runner.Framework")

enablePlugins(JavaAppPackaging)

/**
  * Native Packager config for AWS Lambda:
  * 1.) no top level directory in the zip
  * 2.) this app itself should not be jar'd, the classes and resources should be at the top dir
  * 3.) No docs are needed - just the app + jars
  * 4.) All dependency jars in /lib EXCEPT this apps jar itself (which is built by native packager)
  */
topLevelDirectory := None
mappings in Universal ++= {
  (packageBin in Compile).value
  val t   = target.value
  val dir = t / "scala-2.12" / "classes"
  (dir.allPaths --- dir) pair relativeTo(dir)
}
mappings in (Compile, packageDoc) := Seq()
mappings in Universal := {
  (mappings in Universal).value filter {
    case (_, fname) => !fname.endsWith(s"${name.value}-${version.value}.jar")
  }
}
