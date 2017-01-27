/*
 * Copyright (C) 2017 Dan Di Spaltro
 */
package com.dispalt.pop

import scala.util.Try

trait AppLauncher {
  var lastState: Try[Base]
  def current: Option[Base]
  def get: Try[Base]
}
