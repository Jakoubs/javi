ThisBuild / scalaVersion := "3.3.1"
ThisBuild / version      := "1.0.0"
ThisBuild / organization := "chess"

lazy val root = (project in file("."))
  .settings(
    name := "chess",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    // Enable running with `sbt run`
    Compile / mainClass := Some("chess.Main")
  )
