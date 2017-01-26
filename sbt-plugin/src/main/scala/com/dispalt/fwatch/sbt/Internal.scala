/*
 * Copyright (C) 2017 Dan Di Spaltro
 */
package com.dispalt.fwatch.sbt

import sbt._

object Internal {
  object Configs {
    val DevRuntime = config("dev-mode").hide extend Runtime
  }

  object Keys {
    val stop = TaskKey[Unit]("stop", "Stop services, if have been started in non blocking mode")
  }
}
