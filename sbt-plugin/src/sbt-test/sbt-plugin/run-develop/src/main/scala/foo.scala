package foo

import com.dispalt.fwatch.Base

class NewFoo extends Base {
  def start(cl: ClassLoader) = println("start")
  def stop()                 = println("stop")
  def restart()              = println("restart")
}
