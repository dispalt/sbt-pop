package foo

import com.dispalt.pop.Base

class NewFoo extends Base {
  def start(cl: ClassLoader, port: Int) = println("start")
  def stop()                            = println("stop")
  def restart()                         = println("restart")
}
