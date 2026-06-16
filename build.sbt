ThisBuild / scalaVersion := "3.3.4"
ThisBuild / version      := "1.0.0"

lazy val circeVersion   = "0.14.10"
lazy val http4sVersion  = "0.23.23"
lazy val slickVersion   = "3.5.1"
lazy val mongoVersion   = "5.1.0"
lazy val sparkVersion   = "3.5.3"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-Xmax-inlines", "64"),
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
    coverageExcludedPackages := "chess\\.util\\.parser\\.FastPgnParser",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
      "com.lihaoyi" %% "fastparse" % "3.1.1"
    )
  )

lazy val ai = (project in file("ai"))
  .dependsOn(model, util)
  .settings(
    commonSettings,
    name := "chess-ai",
    coverageExcludedPackages := "chess\\.ai\\.(AlphaBetaAgent|AiEngine|PassiveTrainer|Evaluator)"
  )

lazy val controller = (project in file("controller"))
  .dependsOn(model, util, ai)
  .settings(
    commonSettings,
    name := "chess-controller",
    coverageExcludedPackages := "chess\\.controller\\.(GameController|GameStateResponse|CommandRequest)"
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
  .dependsOn(controller, model, util, ai, persistence, lichess) // ai needed for Evaluator.loadWeights(), persistence for OpeningBook, lichess for Seeder
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
      "ch.qos.logback" %  "logback-classic"     % "1.4.11",
      "com.github.fd4s" %% "fs2-kafka"          % "3.5.0",
      "org.mindrot"    %  "jbcrypt"             % "0.4",
      "com.sun.mail"   %  "jakarta.mail"        % "2.0.1"
    ),
    coverageExcludedPackages := "chess\\.(RestMain|rest\\..*)",
    assembly / mainClass := Some("chess.RestMain"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case PathList("reference.conf")    => MergeStrategy.concat
      case x                             => MergeStrategy.first
    }
  ).settings(Revolver.settings)

lazy val persistence = (project in file("persistence"))
  .dependsOn(model)
  .settings(
    commonSettings,
    name := "chess-persistence",
    coverageExcludedPackages := "chess\\.persistence\\.(mongo.*|PersistenceModule|model\\..*|config\\.mongo|slick\\..*)",
    libraryDependencies ++= Seq(
      // Slick + HikariCP connection pool
      "com.typesafe.slick" %% "slick"                         % slickVersion,
      "com.typesafe.slick" %% "slick-hikaricp"                % slickVersion,
      // PostgreSQL driver (runtime)
      "org.postgresql"      % "postgresql"                     % "42.7.3",
      // H2 for in-memory integration tests
      "com.h2database"      % "h2"                             % "2.2.224"  % Test,
      // MongoDB Reactive Streams driver (pure Java – no Scala wrapper artifact needed)
      "org.mongodb" % "mongodb-driver-reactivestreams" % mongoVersion,
      // Cats Effect – unified IO for both DAOs
      "org.typelevel"      %% "cats-effect"                    % "3.5.4",
      // Typesafe Config (HOCON application.conf)
      "com.typesafe"        % "config"                         % "1.4.3"
    )
  )

lazy val root = (project in file("."))
  .aggregate(util, model, ai, controller, view, rest, lichess, persistence, benchmark, spark)
  .dependsOn(view, rest, lichess)
  .settings(
    commonSettings,
    name := "chess-functional-improvements",
    coverageExcludedPackages := ".*",
    Compile / mainClass := Some("chess.Main")
  )

lazy val lichess = (project in file("lichess"))
  .dependsOn(model, util, ai)
  .settings(
    commonSettings,
    name := "chess-lichess",
    coverageExcludedPackages := "chess\\.lichess.*",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-http"             % "1.0.1",
      "org.apache.pekko" %% "pekko-stream"           % "1.0.1",
      "org.apache.pekko" %% "pekko-actor-typed"      % "1.0.1",
      // Alpakka Kafka: native Pekko Stream ↔ Kafka connector
      "org.apache.pekko" %% "pekko-connectors-kafka" % "1.0.0",
      // Kafka serializers / deserializers (kafka-clients is transitive but explicit is safer)
      "org.apache.kafka"  %  "kafka-clients"          % "3.6.1",
      // Circe for JSON serialization of stream messages
      "io.circe"         %% "circe-core"             % circeVersion,
      "io.circe"         %% "circe-generic"          % circeVersion,
      "io.circe"         %% "circe-parser"           % circeVersion
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case PathList("reference.conf")    => MergeStrategy.concat
      case x                             => MergeStrategy.first
    }
  )

lazy val benchmark = (project in file("benchmark"))
  .enablePlugins(JmhPlugin)
  .dependsOn(model, util)
  .settings(
    commonSettings,
    name := "chess-benchmark",
    coverageExcludedPackages := ".*",
    Compile / doc / sources := Seq.empty,
    publish / skip := true
  )

// ── Spark Analytics (Scala 2.13 – Spark does not support Scala 3) ───────────
//
// Standalone subproject: no dependsOn to model/persistence (those are Scala 3).
// Communicates with the rest of the system via JDBC (Postgres) and Kafka.

lazy val spark = (project in file("spark"))
  .settings(
    scalaVersion := "2.13.14",
    name := "chess-spark",
    Compile / mainClass := Some("chess.spark.SparkMain"),
    // Spark subproject does NOT use commonSettings (those pull in Scala 3 libs).
    // Instead we define its own dependencies here.
    Test / parallelExecution := false,
    Test / logBuffered := false,
    coverageExcludedPackages := "chess\\.spark.*",
    // Spark 3.5.x requires these JVM flags on Java 17+ / 21+
    // (Hadoop's UserGroupInformation uses javax.security.auth.Subject.getSubject
    //  which is removed in newer Java versions)
    Test / javaOptions ++= Seq(
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-opens=java.base/java.net=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
      "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
      "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
      "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
      "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"
    ),
    Test / fork := true,
    run / javaOptions ++= Seq(
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-opens=java.base/java.net=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
      "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
      "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
      "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
      "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"
    ),
    run / fork := true,

    libraryDependencies ++= Seq(
      // Spark
      "org.apache.spark" %% "spark-core"           % sparkVersion,
      "org.apache.spark" %% "spark-sql"            % sparkVersion,
      "org.apache.spark" %% "spark-sql-kafka-0-10" % sparkVersion,
      // Postgres JDBC driver
      "org.postgresql"    % "postgresql"             % "42.7.3",
      // Typesafe Config for application.conf
      "com.typesafe"      % "config"                 % "1.4.3",
      // Test
      "org.scalatest"    %% "scalatest"              % "3.2.17" % Test
    ),
    // Assembly config for fat JAR
    assembly / mainClass := Some("chess.spark.SparkMain"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*)      => MergeStrategy.concat
      case PathList("META-INF", _*)                  => MergeStrategy.discard
      case PathList("reference.conf")                => MergeStrategy.concat
      case PathList("module-info.class")             => MergeStrategy.discard
      case x if x.endsWith(".proto")                => MergeStrategy.first
      case x if x.endsWith(".properties")           => MergeStrategy.first
      case x if x.endsWith(".class")                => MergeStrategy.first
      case x                                         => MergeStrategy.first
    }
  )

