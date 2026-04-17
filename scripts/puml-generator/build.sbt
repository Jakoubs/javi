scalaVersion := "3.3.4"
name := "puml-generator"
version := "1.0.0"

libraryDependencies ++= Seq(
  "org.scalameta" %% "scalameta" % "4.15.1"
)

// Run in SBT process space
Compile / run / fork := false
