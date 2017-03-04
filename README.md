# sbt-pop

### This is alpha software with no published releases

`sbt-pop` makes development reloading faster and therefore much better.  It uses the same
type of mechanism that play uses (and borrows a lot of that code) to achieve "faster" reloads.  Particularly, 
its functionality and requirements of user space code changes are as follows:
* Uses a series of hierarchical classloaders to trash in the event of a reload, well one specifically,
the application classloader.
* Creates **your** class dynamically and instantiates it as a descendent of an interface (which is a java only project)
which supplies the application classloader with the `start` method.
* Has a hook for shutting down, so you can gracefully close things like db connections, etc.
* Uses JDK7 watcher service, so jdk7 minimum.

## How to
* Add the plugin to your `plugins.sbt` file in your project. 
This brings in `build-link` runtime dependency which does not require scala.
* In the sbt project you want to use it, you'll have to call `.enablePlugins(PopPlugin)`.
and also set the `mainClass in run := Some("your.main.class.NOT.OBJECT.which.inherits.from.Base")`.
* You can also watch extra projects like so:

If client is a `scalajs` project, this will add it to the watcher. At the moment the `TaskKey[_]` in the
second _2 spot of the tuple doesn't do anything at the moment.  It will scope the `TaskKey[_]` to 
the project specified in the first spot of the tuple.

```scala
popWatchedProjects ++= Seq(
    (thisProjectRef in client).value -> (fastOptJS in Compile)
),
```

* Make a `class` not an `object`, that inherits from `com.dispalt.pop.Base`
* Start your own custom server in the `start(ClassLoader, port)` method.

The classloader parameter is useful for things like the typesafe config, make sure 
to use the provided `ClassLoader` when dealing with `ConfigFactory.load`

* Destroy resources in the `stop` method.
* The reload method is unused at the moment.
* Port isn't also really necessary to set at the moment, but comes as a parameter.


## Similar Solutions

* [sbt-revolver](https://github.com/spray/sbt-revolver) - Forks and runs
* [play](https://github.com/playframework/playframework) - Great reload experience