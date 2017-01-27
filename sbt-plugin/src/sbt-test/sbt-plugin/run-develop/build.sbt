lazy val root = (project in file(".")).enablePlugins(FastWatch)

scalaVersion := Option(System.getProperty("scala.version")).getOrElse("2.11.8")

mainClass in run := Some("foo.NewFoo")

popStartHook := {
  val log = streams.value.log
  log.info("HELLLLOOO")
}
