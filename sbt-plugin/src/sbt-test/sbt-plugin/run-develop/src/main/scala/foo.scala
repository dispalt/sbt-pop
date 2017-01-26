package foo

import com.dispalt.fwatch.Base

class NewFoo extends Base {
  def start()   = println("start")
  def stop()    = println("stop")
  def restart() = println("restart")
}
