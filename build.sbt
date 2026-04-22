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
    // Standalone GUI client: sbt "view/run"  (REST server must be running on :8080)
    // Note: Not containerised – run locally with `sbt view/run`
    Compile / mainClass := Some("chess.view.Gui"),
    libraryDependencies ++= Seq(
      "org.scalafx" %% "scalafx" % "20.0.0-R31"
    ),
    coverageExcludedPackages := "chess\\.view.*"
  )

lazy val rest = (project in file("rest"))
  .dependsOn(controller, model, util, ai) // ai needed for Evaluator.loadWeights()
  .settings(
    commonSettings,
    name := "chess-rest",
    // Standalone microservice entry point: sbt "rest/run"
    Compile / mainClass := Some("chess.RestMain"),
    libraryDependencies ++= Seq(
      "org.http4s"     %% "http4s-ember-server" % http4sVersion,
      "org.http4s"     %% "http4s-dsl"          % http4sVersion,
      "org.http4s"     %% "http4s-circe"        % http4sVersion,
      "org.typelevel"  %% "cats-effect"         % "3.5.4",
      "ch.qos.logback" %  "logback-classic"     % "1.4.11"
    ),
    coverageExcludedPackages := "chess\\.rest.*",
    assembly / mainClass := Some("chess.RestMain"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case PathList("reference.conf")    => MergeStrategy.concat
      case x                             => MergeStrategy.first
    }
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
      "org.apache.pekko" %% "pekko-http" % "1.0.1",
      "org.apache.pekko" %% "pekko-stream" % "1.0.1",
      "org.apache.pekko" %% "pekko-actor-typed" % "1.0.1"
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case PathList("reference.conf")    => MergeStrategy.concat
      case x                             => MergeStrategy.first
    }
  )
