/*
 * Copyright (C) 2017 Dan Di Spaltro
 */
package com.dispalt.pop

object Threads {

  /**
    * executes given function in the context of the provided classloader
    * @param classloader that should be used to execute given function
    * @param b function to be executed
    */
  def withContextClassLoader[T](classloader: ClassLoader)(b: => T): T = {
    val thread    = Thread.currentThread
    val oldLoader = thread.getContextClassLoader
    try {
      thread.setContextClassLoader(classloader)
      b
    } finally {
      thread.setContextClassLoader(oldLoader)
    }
  }

}
