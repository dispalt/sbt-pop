package example

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.JSApp
import org.scalajs.dom.document

object Main extends JSApp {

  def main(): Unit = {

      dom.document.getElementById("scalajsShoutOut").textContent = "HELLO"

  }

}