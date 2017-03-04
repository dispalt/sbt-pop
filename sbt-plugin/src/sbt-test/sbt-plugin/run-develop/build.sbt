
val client =
  project.in(file("client"))
    .enablePlugins(ScalaJSBundlerPlugin, ScalaJSWeb)
    .settings(
      scalaVersion := Option(System.getProperty("scala.version")).getOrElse("2.11.8"),
      libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "0.9.1",
      npmDependencies in Compile ++= Seq(
        "snabbdom" -> "0.5.3",
        "font-awesome" -> "4.7.0"
      )
    )

val server =
  project.in(file("server"))
    .enablePlugins(PopPlugin, WebScalaJSBundlerPlugin)
    .settings(
      scalaVersion := Option(System.getProperty("scala.version")).getOrElse("2.11.8"),
      mainClass in run := Some("simple.AkkaWeb"),
      libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.0.4",
      scalaJSProjects := Seq(client),
      pipelineStages in Assets := Seq(scalaJSPipeline),
      pipelineStages := Seq(digest, gzip),
      // Expose as sbt-web assets some files retrieved from the NPM packages of the `client` project
      npmAssets ++= NpmAssets.ofProject(client) { modules => (modules / "font-awesome").*** }.value,
      WebKeys.packagePrefix in Assets := "public/",
      WebKeys.exportedMappings in Assets ++= {
        for ((file, path) <- (mappings in Assets).value)
          yield file -> ((WebKeys.packagePrefix in Assets).value + path)
      }
    )


lazy val root = (project in file(".")).aggregate(client, server)