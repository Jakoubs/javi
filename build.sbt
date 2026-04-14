ThisBuild / scalaVersion := "3.3.4"
ThisBuild / version      := "1.0.0"



lazy val root = (project in file("."))
  .settings(
    name := "chess-functional-improvements",
    libraryDependencies ++= Seq(
      "org.scalafx" %% "scalafx" % "20.0.0-R31",
      // Web API (Pekko HTTP + circe)
      "org.apache.pekko"       %% "pekko-http"          % "1.0.1",
      "org.apache.pekko"       %% "pekko-stream"        % "1.0.2",
      "org.apache.pekko"       %% "pekko-actor-typed"   % "1.0.2",
      "io.circe"               %% "circe-core"          % "0.14.10",
      "io.circe"               %% "circe-generic"       % "0.14.10",
      "io.circe"               %% "circe-parser"        % "0.14.10",

      // Testing dependencies
      "org.scalatest" %% "scalatest" % "3.2.17" % Test,
      "org.scalatestplus" %% "scalacheck-1-17" % "3.2.17.0" % Test,
      // Parser libraries
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
      "com.lihaoyi" %% "fastparse" % "3.1.1"
    ),
    // Enable running with `sbt run`
    Compile / mainClass := Some("chess.Main"),
    // Test configuration
    Test / parallelExecution := false,
    Test / logBuffered := false,
    // Coverage configuration
    coverageExcludedPackages := "chess\\.view.*",
      
    coverageMinimumStmtTotal := 85,
    coverageMinimumBranchTotal := 75,
    coverageFailOnMinimum := false,
    coverageHighlighting := true
  )
