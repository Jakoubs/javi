ThisBuild / scalaVersion := "3.3.4"
ThisBuild / version      := "1.0.0"

lazy val circeVersion = "0.14.10"
lazy val http4sVersion = "0.23.23"

lazy val commonSettings = Seq(
  Test / parallelExecution := false,
  Test / logBuffered := false,
  coverageMinimumStmtTotal := 85,
  coverageMinimumBranchTotal := 75,
  coverageFailOnMinimum := false,
  coverageHighlighting := true,
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.17" % Test,
    "org.scalatestplus" %% "scalacheck-1-17" % "3.2.17.0" % Test,
    "io.circe"   %% "circe-core"          % circeVersion,
    "io.circe"   %% "circe-generic"       % circeVersion,
    "io.circe"   %% "circe-parser"        % circeVersion
  )
)

lazy val model = (project in file("model"))
  .settings(
    commonSettings,
    name := "chess-model"
  )

lazy val util = (project in file("util"))
  .dependsOn(model)
  .settings(
    commonSettings,
    name := "chess-util",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
      "com.lihaoyi" %% "fastparse" % "3.1.1"
    )
  )

lazy val ai = (project in file("ai"))
  .dependsOn(model, util)
  .settings(
    commonSettings,
    name := "chess-ai"
  )

lazy val controller = (project in file("controller"))
  .dependsOn(model, util, ai)
  .settings(
    commonSettings,
    name := "chess-controller"
  )

lazy val view = (project in file("view"))
  .dependsOn(controller, model, util)
  .settings(
    commonSettings,
    name := "chess-view",
    libraryDependencies ++= Seq(
      "org.scalafx" %% "scalafx" % "20.0.0-R31"
    ),
    coverageExcludedPackages := "chess\\.view.*"
  )

lazy val rest = (project in file("rest"))
  .dependsOn(controller, model, util)
  .settings(
    commonSettings,
    name := "chess-rest",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl"          % http4sVersion,
      "org.http4s" %% "http4s-circe"        % http4sVersion,
      "ch.qos.logback" % "logback-classic"  % "1.4.11" // Added for Http4s logging
    ),
    coverageExcludedPackages := "chess\\.rest.*"
  )

lazy val root = (project in file("."))
  .aggregate(util, model, ai, controller, view, rest, lichess)
  .dependsOn(view, rest, lichess)
  .settings(
    commonSettings,
    name := "chess-functional-improvements",
    Compile / mainClass := Some("chess.Main")
  )

lazy val lichess = (project in file("lichess"))
  .dependsOn(model, util, ai)
  .settings(
    commonSettings,
    name := "chess-lichess",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-circe"        % http4sVersion,
      "org.http4s" %% "http4s-dsl"          % http4sVersion,
      "co.fs2"     %% "fs2-io"              % "3.9.4"
    )
  )

addCommandAlias("fullCoverage", ";coverage;test;coverageAggregate")
