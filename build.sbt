ThisBuild / tlBaseVersion := "0.1"

ThisBuild / organization := "io.github.alixba"
ThisBuild / organizationName := "AlixBa"
ThisBuild / startYear := Some(2025)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  tlGitHubDev("alixba", "AlixBa")
)

ThisBuild / tlSitePublishBranch := Some("main")
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("21"))

val Scala213 = "2.13.16"
ThisBuild / crossScalaVersions := Seq(Scala213, "3.7.3")
ThisBuild / scalaVersion := Scala213

lazy val root = tlCrossRootProject.aggregate(
  core,
  `doobie-postgres`,
  examples,
  otel4s
)

lazy val core = project
  .in(file("modules/core"))
  .settings(
    name := "idempotency-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % "2.13.0",
      "org.typelevel" %%% "cats-effect" % "3.6.3",
      "org.scalameta" %%% "munit" % "1.2.0" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.1.0" % Test
    ),
    Test / fork := true
  )

lazy val `doobie-postgres` = project
  .in(file("modules/doobie-postgres"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "idempotency-doobie-postgres",
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-core" % "1.0.0-RC10",
      "org.tpolecat" %% "doobie-postgres" % "1.0.0-RC10",
      "org.typelevel" %%% "cats-core" % "2.13.0",
      "org.typelevel" %%% "cats-effect" % "3.6.3",
      "com.dimafeng" %% "testcontainers-scala-munit" % "0.43.0" % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % "0.43.0" % Test,
      "org.scalameta" %%% "munit" % "1.2.0" % Test,
      "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC10" % Test,
      "org.tpolecat" %% "doobie-munit" % "1.0.0-RC10" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.1.0" % Test
    ),
    Test / fork := true
  )

lazy val otel4s = project
  .in(file("modules/otel4s"))
  .dependsOn(core)
  .settings(
    name := "idempotency-otel4s",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "otel4s-core" % "0.13.1",
      "org.scalameta" %%% "munit" % "1.2.0" % Test,
      "org.typelevel" %% "otel4s-sdk-testkit" % "0.13.1" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.1.0" % Test
    )
  )

lazy val examples = project
  .in(file("examples"))
  .dependsOn(core, `doobie-postgres`, otel4s)
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "idempotency-examples",
    libraryDependencies ++= Seq(
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % "1.54.0",
      "org.tpolecat" %% "doobie-core" % "1.0.0-RC10",
      "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC10",
      "org.tpolecat" %% "doobie-postgres" % "1.0.0-RC10",
      "org.typelevel" %% "otel4s-oteljava" % "0.13.1",
      "org.typelevel" %% "otel4s-oteljava-context-storage" % "0.13.1",
      "org.typelevel" %%% "cats-core" % "2.13.0",
      "org.typelevel" %%% "cats-effect" % "3.6.3"
    ),
    javaOptions ++= Seq(
      "-Dcats.effect.trackFiberContext=true",
      "-Dotel.java.global-autoconfigure.enabled=true",
      "-Dotel.service.name=idempotency-examples"
    ),
    run / fork := true
  )

lazy val docs = project
  .in(file("site"))
  .dependsOn(core)
  .enablePlugins(TypelevelSitePlugin)
  .settings(
    name := "idempotency-docs",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % "2.13.0" % Provided,
      "org.typelevel" %%% "cats-effect" % "3.6.3" % Provided
    )
  )
