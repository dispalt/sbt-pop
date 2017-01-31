import de.heikoseeberger.sbtheader.HeaderPattern

val ScalaTestVersion        = "3.0.1"
val ScalaJava8CompatVersion = "0.7.0"
val BetterFiles             = "2.14.0"

val scalaTest        = "org.scalatest"          %% "scalatest"          % ScalaTestVersion
val scalaJava8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % ScalaJava8CompatVersion

def common: Seq[Setting[_]] = releaseSettings ++ bintraySettings ++ Seq(
  organization := "com.dispalt.pop",
  // Must be "Apache-2.0", because bintray requires that it is a license that it knows about
  licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))),
  homepage := Some(url("http://www.github.com/sbt-pop")),
  sonatypeProfileName := "com.lightbend",
  headers := headers.value ++ Map(
    "scala" -> (
      HeaderPattern.cStyleBlockComment,
      """|/*
         | * Copyright (C) 2017 Dan Di Spaltro
         | */
         |""".stripMargin
    ),
    "java" -> (
      HeaderPattern.cStyleBlockComment,
      """|/*
         | * Copyright (C) 2017 Dan Di Spaltro
         | */
         |""".stripMargin
    )
  ),
  pomExtra := {
    <scm>
      <url>https://github.com/dispalt/sbt-pop</url>
      <connection>scm:git:git@github.com:dispalt/sbt-pop.git</connection>
    </scm>
      <developers>
        <developer>
          <id>dispalt</id>
          <name>Dan Di Spaltro</name>
          <url>https://github.com/dispalt</url>
        </developer>
      </developers>
  },
  pomIncludeRepository := { _ =>
    false
  },
  concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
  // Setting javac options in common allows IntelliJ IDEA to import them automatically
  javacOptions in compile ++= Seq(
    "-encoding",
    "UTF-8",
    "-source",
    "1.8",
    "-target",
    "1.8",
    "-parameters",
    "-Xlint:unchecked",
    "-Xlint:deprecation"
  )
)

def bintraySettings: Seq[Setting[_]] = Seq(
  bintrayOrganization := Some("dispalt"),
  bintrayRepository := "sbt-plugins",
  bintrayPackage := "sbt-pop",
  bintrayReleaseOnPublish := false
)

def releaseSettings: Seq[Setting[_]] = Seq(
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  releaseTagName := (version in ThisBuild).value,
  releaseProcess := {
    import ReleaseTransformations._

    Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      releaseStepTask(bintrayRelease in thisProjectRef.value),
      releaseStepCommand("sonatypeRelease"),
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  }
)

def runtimeLibCommon: Seq[Setting[_]] = common ++ Seq(
  crossScalaVersions := Seq("2.11.8"),
  scalaVersion := crossScalaVersions.value.head,
  crossVersion := CrossVersion.binary,
  crossPaths := false,
//  dependencyOverrides += "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
//  dependencyOverrides += "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,

  // compile options
  scalacOptions in Compile ++= Seq("-encoding",
                                   "UTF-8",
                                   "-target:jvm-1.8",
                                   "-feature",
                                   "-unchecked",
                                   "-Xlog-reflective-calls",
                                   "-Xlint",
                                   "-deprecation"),
  incOptions := incOptions.value.withNameHashing(true),
  // show full stack traces and test case durations
  testOptions in Test += Tests.Argument("-oDF")
)

// Publisher switching
def RuntimeLibPlugins = AutomateHeaderPlugin && Sonatype && PluginsAccessor.exclude(BintrayPlugin)
def SbtPluginPlugins  = AutomateHeaderPlugin && BintrayPlugin && PluginsAccessor.exclude(Sonatype)

val otherProjects = Seq[Project](
  `build-link`,
  `reloadable-server`,
  `sbt-plugin`
)

lazy val root = (project in file("."))
  .settings(name := "pop")
  .settings(
    PgpKeys.publishSigned := {},
    publishLocal := {},
    publishArtifact in Compile := false,
    publish := {}
  )
  .aggregate(otherProjects.map(Project.projectToRef): _*)

//def SbtPlugins = PluginsAccessor.exclude(Build)
//

lazy val `build-link` = (project in file("build-link"))
  .enablePlugins(RuntimeLibPlugins)
  .settings(common: _*)
  .settings(
    crossPaths := false,
    autoScalaLibrary := false
  )

lazy val `reloadable-server` = (project in file("reloadable-server"))
  .settings(name := "pop-reloadable-server")
  .settings(runtimeLibCommon: _*)
  .enablePlugins(RuntimeLibPlugins)
  .dependsOn(`build-link`)

lazy val `sbt-plugin` = (project in file("sbt-plugin"))
  .settings(name := "sbt-pop")
  .settings(common: _*)
  .settings(scriptedSettings: _*)
  .enablePlugins(SbtPluginPlugins)
  .settings(
    sbtPlugin := true,
    libraryDependencies ++= Seq(
      scalaTest              % Test,
      "com.github.pathikrit" %% "better-files" % "2.14.0"
    ),
    sourceGenerators in Compile += Def.task {
      Generators.version(version.value, (sourceManaged in Compile).value)
    }.taskValue,
    scriptedDependencies := {
      val () = scriptedDependencies.value
      val () = publishLocal.value
      val () = (publishLocal in `build-link`).value
      val () = (publishLocal in `reloadable-server`).value
    },
    publishTo := {
      if (isSnapshot.value) {
        // Bintray doesn't support publishing snapshots, publish to Sonatype snapshots instead
        Some(Opts.resolver.sonatypeSnapshots)
      } else publishTo.value
    },
    publishMavenStyle := isSnapshot.value
  )
  .dependsOn(`build-link`)

def scriptedSettings: Seq[Setting[_]] =
  ScriptedPlugin.scriptedSettings ++
    Seq(scriptedLaunchOpts += s"-Dproject.version=${version.value}") ++
    Seq(
      scripted := scripted.tag(Tags.Test).evaluated,
      scriptedBufferLog := false,
      scriptedLaunchOpts ++= Seq(
        "-Xmx768m",
        "-XX:MaxMetaspaceSize=384m",
        "-Dscala.version=" + sys.props
          .get("scripted.scala.version")
          .getOrElse((scalaVersion in `reloadable-server`).value)
      )
    )
