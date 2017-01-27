import sbt._

object Generators {
  // Generates a scala file that contains the Lagom version for use at runtime.
  def version(lagomVersion: String, dir: File): Seq[File] = {
    val file = dir / "com" / "dispalt" / "fwatch" / "core" / "FastWatchVersion.scala"
    val scalaSource =
      """|package com.dispalt.pop.core
         |
         |object FastWatchVersion {
         |    val current = "%s"
         |}
        """.stripMargin.format(lagomVersion)

    if (!file.exists() || IO.read(file) != scalaSource) {
      IO.write(file, scalaSource)
    }

    Seq(file)
  }
}
