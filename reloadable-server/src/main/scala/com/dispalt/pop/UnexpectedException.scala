/*
 * Copyright (C) 2017 Dan Di Spaltro
 */
package com.dispalt.pop

case class UnexpectedException(message: Option[String] = None, unexpected: Option[Throwable] = None)
    extends PlayException(
      "Unexpected exception",
      message.getOrElse {
        unexpected.map(t => "%s: %s".format(t.getClass.getSimpleName, t.getMessage)).getOrElse("")
      },
      unexpected.orNull
    )
