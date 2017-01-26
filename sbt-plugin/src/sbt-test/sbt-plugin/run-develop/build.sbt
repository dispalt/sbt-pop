lazy val root = (project in file(".")).enablePlugins(FastWatch)

scalaVersion := Option(System.getProperty("scala.version")).getOrElse("2.11.7")

mainClass in run := Some("foo.NewFoo")
