ThisBuild / scalaVersion := "3.3.1"
ThisBuild / version      := "1.0.0"
ThisBuild / organization := "chess"

lazy val root = (project in file("."))
  .settings(
    name := "chess",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-swing" % "3.0.0",
      // Web API (http4s + circe)
      "org.http4s"             %% "http4s-ember-server" % "0.23.30",
      "org.http4s"             %% "http4s-dsl"          % "0.23.30",
      "org.http4s"             %% "http4s-circe"        % "0.23.30",
      "io.circe"               %% "circe-core"          % "0.14.10",
      "io.circe"               %% "circe-generic"       % "0.14.10",
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    // Enable running with `sbt run`
    Compile / mainClass := Some("chess.Main")
  )
