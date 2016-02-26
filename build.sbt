name := """s3upload"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.6"

libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws,
  "com.amazonaws" % "aws-java-sdk" % "1.6.8"
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
