/*
 * Copyright (C) 2017 Dan Di Spaltro
 */
package com.dispalt.fwatch.sbt

import java.io.{ Closeable, File }
import java.net.URL
import java.security.{ AccessController, PrivilegedAction }
import java.time.Instant
import java.util
import java.util.{ Timer, TimerTask }
import java.util.concurrent.atomic.AtomicReference

import Reloader._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import sbt._
import sbt.Keys._
import better.files.{ File => ScalaFile, _ }
import FastWatch.autoImport._
import com.dispalt.fwatch.PlayException
import com.dispalt.fwatch.core.BuildLink
import com.dispalt.fwatch.sbt.PlayExceptions.{ CompilationException, UnexpectedException }
import com.dispalt.fwatch.sbt.server.ReloadableServer

object Reloader {

  sealed trait CompileResult
  case class CompileSuccess(sources: Map[String, Source], classpath: Seq[File]) extends CompileResult
  case class CompileFailure(exception: Exception)                               extends CompileResult

  case class Source(file: File, original: Option[File])

  private val accessControlContext = AccessController.getContext

  /**
    * Execute f with context ClassLoader of Reloader
    */
  private def withReloaderContextClassLoader[T](f: => T): T = {
    val thread    = Thread.currentThread
    val oldLoader = thread.getContextClassLoader
    // we use accessControlContext & AccessController to avoid a ClassLoader leak (ProtectionDomain class)
    AccessController.doPrivileged(
      new PrivilegedAction[T]() {
        def run: T = {
          try {
            thread.setContextClassLoader(classOf[Reloader].getClassLoader)
            f
          } finally {
            thread.setContextClassLoader(oldLoader)
          }
        }
      },
      accessControlContext
    )
  }

  private def urls(cp: Seq[File]): Array[URL] = cp.map(_.toURI.toURL).toArray

  /**
    * Play dev server
    */
  trait DevServer extends Closeable {
    val buildLink: BuildLink

    /** Allows to register a listener that will be triggered a monitored file is changed. */
    def addChangeListener(f: () => Unit): Unit

    /** Reloads the application.*/
    def reload(): Unit

    /** URL at which the application is running (if started) */
    def url(): String
  }

  /**
    * Start the Lagom server in dev mode.
    */
  def startDevMode(
      parentClassLoader: ClassLoader,
      dependencyClasspath: Seq[File],
      reloadCompile: () => CompileResult,
      classLoaderDecorator: ClassLoader => ClassLoader,
      monitoredFiles: Seq[File],
      fileWatchService: JDK7FileWatchService,
      projectPath: File,
      devSettings: Seq[(String, String)],
      httpPort: Int,
      reloadLock: AnyRef,
      spawnMainClass: String
  ): DevServer = {
    /*
     * We need to do a bit of classloader magic to run the Play application.
     *
     * There are seven classloaders:
     *
     * 1. buildLoader, the classloader of the build tool plugin (sbt/maven lagom plugin).
     * 2. parentClassLoader, a possibly shared classloader that may contain artifacts
     *    that are known to not share state, eg Scala itself.
     * 3. delegatingLoader, a special classloader that overrides class loading
     *    to delegate shared classes for build link to the buildLoader, and accesses
     *    the reloader.currentApplicationClassLoader for resource loading to
     *    make user resources available to dependency classes.
     * 4. applicationLoader, contains the application dependencies. Has the
     *    delegatingLoader as its parent. Classes from the commonLoader and
     *    the delegatingLoader are checked for loading first.
     * 5. decoratedClassloader, allows the classloader to be decorated.
     * 6. reloader.currentApplicationClassLoader, contains the user classes
     *    and resources. Has applicationLoader as its parent, where the
     *    application dependencies are found, and which will delegate through
     *    to the buildLoader via the delegatingLoader for the shared link.
     *    Resources are actually loaded by the delegatingLoader, where they
     *    are available to both the reloader and the applicationLoader.
     *    This classloader is recreated on reload. See PlayReloader.
     *
     * Someone working on this code in the future might want to tidy things up
     * by splitting some of the custom logic out of the URLClassLoaders and into
     * their own simpler ClassLoader implementations. The curious cycle between
     * applicationLoader and reloader.currentApplicationClassLoader could also
     * use some attention.
     */

    val buildLoader = this.getClass.getClassLoader

    /**
      * ClassLoader that delegates loading of shared build link classes to the
      * buildLoader. Also accesses the reloader resources to make these available
      * to the applicationLoader, creating a full circle for resource loading.
      */
    lazy val delegatingLoader: ClassLoader = new DelegatingClassLoader(
      parentClassLoader,
      com.dispalt.fwatch.core.Build.sharedClasses.asScala.toSet,
      buildLoader,
      reloader.getClassLoader _
    )

    lazy val applicationLoader =
      new NamedURLClassLoader("FastWatchDependencyClassLoader", urls(dependencyClasspath), delegatingLoader)
    lazy val decoratedLoader = classLoaderDecorator(applicationLoader)

    lazy val reloader = new Reloader(reloadCompile,
                                     decoratedLoader,
                                     projectPath,
                                     devSettings,
                                     monitoredFiles,
                                     fileWatchService,
                                     reloadLock)

    val server = {
      val mainClass = applicationLoader.loadClass("com.dispalt.server.FastWatchServerStart")
      val mainDev   = mainClass.getMethod("mainDevHttpMode", classOf[BuildLink], classOf[Int], classOf[String])
      mainDev.invoke(null, reloader, httpPort: java.lang.Integer, spawnMainClass).asInstanceOf[ReloadableServer]
    }

    server.reload()

    new DevServer {
      val buildLink: BuildLink                   = reloader
      def addChangeListener(f: () => Unit): Unit = reloader.addChangeListener(f)
      def reload(): Unit                         = server.reload()
      def close(): Unit = {
        server.stop()
        reloader.close()
      }
      def url(): String = server.mainAddress().getHostName + ":" + server.mainAddress().getPort
    }
  }

}

class Reloader(
    reloadCompile: () => CompileResult,
    baseLoader: ClassLoader,
    val projectPath: File,
    devSettings: Seq[(String, String)],
    monitoredFiles: Seq[File],
    fileWatchService: JDK7FileWatchService,
    reloadLock: AnyRef
) extends BuildLink {

  // The current classloader for the application
  @volatile private var currentApplicationClassLoader: Option[ClassLoader] = None
  // Flag to force a reload on the next request.
  // This is set if a compile error occurs, and also by the forceReload method on BuildLink, which is called for
  // example when evolutions have been applied.
  @volatile private var forceReloadNextTime = false
  // Whether any source files have changed since the last request.
  @volatile private var changed = false
  // The last successful compile results. Used for rendering nice errors.
  @volatile private var currentSourceMap = Option.empty[Map[String, Source]]
  // A watch state for the classpath. Used to determine whether anything on the classpath has changed as a result
  // of compilation, and therefore a new classloader is needed and the app needs to be reloaded.
  @volatile private var watchState: WatchState = WatchState.empty

  // Stores the most recent time that a file was changed
  private val fileLastChanged = new AtomicReference[Instant]()

  // Create the watcher, updates the changed boolean when a file has changed.
  private val watcher = fileWatchService.watch(monitoredFiles, () => {

    changed = true
    onChange()
  })
  private val classLoaderVersion = new java.util.concurrent.atomic.AtomicInteger(0)

  private val quietTimeTimer = new Timer("reloader-timer", true)

  private val listeners = new java.util.concurrent.CopyOnWriteArrayList[() => Unit]()

  private val quietPeriodMs = 200l
  private def onChange(): Unit = {
    val now = Instant.now()
    fileLastChanged.set(now)
    // set timer task
    quietTimeTimer.schedule(new TimerTask {
      override def run(): Unit = quietPeriodFinished(now)
    }, quietPeriodMs)
  }

  private def quietPeriodFinished(start: Instant): Unit = {
    // If our start time is equal to the most recent start time stored, then execute the handlers and set the most
    // recent time to null, otherwise don't do anything.
    if (fileLastChanged.compareAndSet(start, null)) {
      import scala.collection.JavaConverters._
      listeners.iterator().asScala.foreach(listener => listener())
    }
  }

  def addChangeListener(f: () => Unit): Unit = listeners.add(f)

  /**
    * Contrary to its name, this doesn't necessarily reload the app.  It is invoked on every request, and will only
    * trigger a reload of the app if something has changed.
    *
    * Since this communicates across classloaders, it must return only simple objects.
    *
    *
    * @return Either
    * - Throwable - If something went wrong (eg, a compile error).
    * - ClassLoader - If the classloader has changed, and the application should be reloaded.
    * - null - If nothing changed.
    */
  def reload: AnyRef = {
    reloadLock.synchronized {
      if (changed || forceReloadNextTime || currentSourceMap.isEmpty || currentApplicationClassLoader.isEmpty) {
        val shouldReload = forceReloadNextTime

        changed = false
        forceReloadNextTime = false

        // use Reloader context ClassLoader to avoid ClassLoader leaks in sbt/scala-compiler threads
        Reloader.withReloaderContextClassLoader {
          // Run the reload task, which will trigger everything to compile
          reloadCompile() match {
            case CompileFailure(exception) =>
              // We force reload next time because compilation failed this time
              forceReloadNextTime = true
              exception

            case CompileSuccess(sourceMap, classpath) =>
              currentSourceMap = Some(sourceMap)

              // We only want to reload if the classpath has changed.  Assets don't live on the classpath, so
              // they won't trigger a reload.
              // Use the SBT watch service, passing true as the termination to force it to break after one check
              val (_, newState) = SourceModificationWatch.watch(() =>
                                                                  classpath.iterator
                                                                    .filter(_.exists())
                                                                    .flatMap(_.toScala.listRecursively),
                                                                0,
                                                                watchState)(true)
              // SBT has a quiet wait period, if that's set to true, sources were modified
              val triggered = newState.awaitingQuietPeriod
              watchState = newState

              if (triggered || shouldReload || currentApplicationClassLoader.isEmpty) {
                // Create a new classloader
                val version = classLoaderVersion.incrementAndGet
                val name    = "ReloadableClassLoader(v" + version + ")"
                val urls    = Reloader.urls(classpath)
                val loader  = new NamedURLClassLoader(name, urls, baseLoader)
                currentApplicationClassLoader = Some(loader)
                loader
              } else {
                null // null means nothing changed
              }
          }
        }
      } else {
        null // null means nothing changed
      }
    }
  }

  lazy val settings = {
    import scala.collection.JavaConverters._
    devSettings.toMap.asJava
  }

  def forceReload() {
    forceReloadNextTime = true
  }

  def findSource(className: String, line: java.lang.Integer): Array[java.lang.Object] = {
    val topType = className.split('$').head
    currentSourceMap.flatMap { sources =>
      sources.get(topType).map { source =>
        Array[java.lang.Object](source.original.getOrElse(source.file), line)
      }
    }.orNull
  }

  def runTask(task: String): AnyRef =
    throw new UnsupportedOperationException("This BuildLink does not support running arbitrary tasks")

  def close() = {
    currentApplicationClassLoader = None
    currentSourceMap = None
    watcher.stop()
    quietTimeTimer.cancel()
  }

  def getClassLoader = currentApplicationClassLoader
}

import java.net.{ URL, URLClassLoader }
import java.util

/**
  * A ClassLoader with a toString() that prints name/urls. Copied from Play
  */
class NamedURLClassLoader(name: String, urls: Array[URL], parent: ClassLoader) extends URLClassLoader(urls, parent) {
  override def toString = name + "{" + getURLs.map(_.toString).mkString(", ") + "}"
}

class DelegatingClassLoader(commonLoader: ClassLoader,
                            sharedClasses: Set[String],
                            buildLoader: ClassLoader,
                            applicationClassLoader: () => Option[ClassLoader])
    extends ClassLoader(commonLoader) {

  lazy val findResourceMethod = {
    val method = classOf[ClassLoader].getDeclaredMethod("findResource", classOf[String])
    method.setAccessible(true)
    method
  }

  lazy val findResourcesMethod = {
    val method = classOf[ClassLoader].getDeclaredMethod("findResources", classOf[String])
    method.setAccessible(true)
    method
  }

  override def loadClass(name: String, resolve: Boolean) = {
    if (sharedClasses(name)) {
      buildLoader.loadClass(name)
    } else {
      super.loadClass(name, resolve)
    }
  }

  override def getResource(name: String) = {
    applicationClassLoader()
      .flatMap(cl => Option(findResourceMethod.invoke(cl, name).asInstanceOf[URL]))
      .getOrElse(super.getResource(name))
  }

  override def getResources(name: String) = {
    val appResources = applicationClassLoader().fold(new util.Vector[URL]().elements) { cl =>
      findResourcesMethod.invoke(cl, name).asInstanceOf[util.Enumeration[URL]]
    }
    val superResources = super.getResources(name)
    val resources      = new util.Vector[URL]()
    while (appResources.hasMoreElements) resources.add(appResources.nextElement())
    while (superResources.hasMoreElements) resources.add(superResources.nextElement())
    resources.elements()
  }

  override def toString = "DelegatingClassLoader, using parent: " + getParent
}

/**
  * Copied from sbt.
  *
  *
  *
  *
  *
  *
  *
  *
  */
object SourceModificationWatch {
  type PathFinder = () => Iterator[ScalaFile]

  @tailrec
  def watch(sourcesFinder: PathFinder, pollDelayMillis: Int, state: WatchState)(
      terminationCondition: => Boolean
  ): (Boolean, WatchState) = {
    import state._

    val sourceFilesPath: Set[String] = sourcesFinder().map(_.toJava.getCanonicalPath).toSet
    val lastModifiedTime =
      (0L /: sourcesFinder()) { (acc, file) =>
        math.max(acc, file.lastModifiedTime.toEpochMilli)
      }

    val sourcesModified =
      lastModifiedTime > lastCallbackCallTime ||
        previousFiles != sourceFilesPath

    val (triggered, newCallbackCallTime) =
      if (sourcesModified)
        (false, System.currentTimeMillis)
      else
        (awaitingQuietPeriod, lastCallbackCallTime)

    val newState =
      new WatchState(newCallbackCallTime, sourceFilesPath, sourcesModified, if (triggered) count + 1 else count)
    if (triggered)
      (true, newState)
    else {
      Thread.sleep(pollDelayMillis)
      if (terminationCondition)
        (false, newState)
      else
        watch(sourcesFinder, pollDelayMillis, newState)(terminationCondition)
    }
  }
}

/**
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  */
final class WatchState(val lastCallbackCallTime: Long,
                       val previousFiles: Set[String],
                       val awaitingQuietPeriod: Boolean,
                       val count: Int) {
  def previousFileCount: Int = previousFiles.size
}

object WatchState {
  def empty = new WatchState(0L, Set.empty[String], false, 0)
}

/**
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  */
object RunSupport {
  import FastWatch.autoImport._

  def reloadRunTask(
      extraConfigs: Map[String, String]
  ): Def.Initialize[Task[Reloader.DevServer]] = Def.task {

    val state = Keys.state.value
    val scope = resolvedScoped.value.scope

    val reloadCompile = () =>
      RunSupport.compile(
        () => Project.runTask(fwCompileEverything in scope, state).map(_._2).get,
        () => Project.runTask(fwReloaderClasspath in scope, state).map(_._2).get,
        () => Project.runTask(streamsManager in scope, state).map(_._2).get.toEither.right.toOption
    )

    val classpath = (devModeDependencies.value ++ (externalDependencyClasspath in Runtime).value).distinct.files
    val mc        = (mainClass in Keys.run).value.getOrElse(sys.error("Missing (mainClass in run) not set."))

    Reloader.startDevMode(
      scalaInstance.value.loader,
      classpath,
      reloadCompile,
      fwClassLoaderDecorator.value,
      fwMonitoredProjectDirs.value.flatMap(_._2),
      fwWatcherService.value,
      baseDirectory.value,
      extraConfigs.toSeq,
      8080,
      RunSupport,
      mc
    )
  }

  private def devModeDependencies = Def.task {
    (managedClasspath in Internal.Configs.DevRuntime).value
  }

  def compile(reloadCompile: () => Result[sbt.inc.Analysis],
              classpath: () => Result[Classpath],
              streams: () => Option[Streams]): CompileResult = {
    reloadCompile().toEither.left
      .map(compileFailure(streams()))
      .right
      .map { analysis =>
        classpath().toEither.left
          .map(compileFailure(streams()))
          .right
          .map { classpath =>
            CompileSuccess(sourceMap(analysis), classpath.files)
          }
          .fold(identity, identity)
      }
      .fold(identity, identity)
  }

  def sourceMap(analysis: sbt.inc.Analysis): Map[String, Source] = {
    analysis.apis.internal.foldLeft(Map.empty[String, Source]) {
      case (sourceMap, (file, source)) =>
        sourceMap ++ {
          source.api.definitions map { d =>
            d.name -> Source(file, originalSource(file))
          }
        }
    }
  }

  def originalSource(file: File): Option[File] = {
    //play.twirl.compiler.MaybeGeneratedSource.unapply(file).map(_.file)
    // TODO: Fix
    Some(file)
  }

  def compileFailure(streams: Option[Streams])(incomplete: Incomplete): CompileResult = {
    CompileFailure(taskFailureHandler(incomplete, streams))
  }

  def taskFailureHandler(incomplete: Incomplete, streams: Option[Streams]): PlayException = {
    Incomplete
      .allExceptions(incomplete)
      .headOption
      .map {
        case e: PlayException => e
        case e: xsbti.CompileFailed =>
          getProblems(incomplete, streams)
            .find(_.severity == xsbti.Severity.Error)
            .map(CompilationException)
            .getOrElse(UnexpectedException(Some("The compilation failed without reporting any problem!"), Some(e)))
        case e: Exception => UnexpectedException(unexpected = Some(e))
      }
      .getOrElse {
        UnexpectedException(Some("The compilation task failed without any exception!"))
      }
  }

  def getScopedKey(incomplete: Incomplete): Option[ScopedKey[_]] = incomplete.node flatMap {
    case key: ScopedKey[_] => Option(key)
    case task: Task[_]     => task.info.attributes get taskDefinitionKey
  }

  def getProblems(incomplete: Incomplete, streams: Option[Streams]): Seq[xsbti.Problem] = {
    allProblems(incomplete) ++ {
      Incomplete.linearize(incomplete).flatMap(getScopedKey).flatMap { scopedKey =>
        val JavacError         = """\[error\]\s*(.*[.]java):(\d+):\s*(.*)""".r
        val JavacErrorInfo     = """\[error\]\s*([a-z ]+):(.*)""".r
        val JavacErrorPosition = """\[error\](\s*)\^\s*""".r

        streams
          .map { streamsManager =>
            var first: (Option[(String, String, String)], Option[Int])  = (None, None)
            var parsed: (Option[(String, String, String)], Option[Int]) = (None, None)
            Output
              .lastLines(scopedKey, streamsManager, None)
              .map(_.replace(scala.Console.RESET, ""))
              .map(_.replace(scala.Console.RED, ""))
              .collect {
                case JavacError(file, line, message) => parsed = Some((file, line, message)) -> None
                case JavacErrorInfo(key, message) =>
                  parsed._1.foreach { o =>
                    parsed = Some(
                        (parsed._1.get._1,
                         parsed._1.get._2,
                         parsed._1.get._3 + " [" + key.trim + ": " + message.trim + "]")
                      ) -> None
                  }
                case JavacErrorPosition(pos) =>
                  parsed = parsed._1 -> Some(pos.size)
                  if (first == ((None, None))) {
                    first = parsed
                  }
              }
            first
          }
          .collect {
            case (Some(error), maybePosition) =>
              new xsbti.Problem {
                def message  = error._3
                def category = ""
                def position = new xsbti.Position {
                  def line        = xsbti.Maybe.just(error._2.toInt)
                  def lineContent = ""
                  def offset      = xsbti.Maybe.nothing[java.lang.Integer]
                  def pointer =
                    maybePosition
                      .map(pos => xsbti.Maybe.just((pos - 1).asInstanceOf[java.lang.Integer]))
                      .getOrElse(xsbti.Maybe.nothing[java.lang.Integer])
                  def pointerSpace = xsbti.Maybe.nothing[String]
                  def sourceFile   = xsbti.Maybe.just(file(error._1))
                  def sourcePath   = xsbti.Maybe.just(error._1)
                }
                def severity = xsbti.Severity.Error
              }
          }

      }
    }
  }

  def allProblems(inc: Incomplete): Seq[xsbti.Problem] = {
    allProblems(inc :: Nil)
  }

  def allProblems(incs: Seq[Incomplete]): Seq[xsbti.Problem] = {
    problems(Incomplete.allExceptions(incs).toSeq)
  }

  def problems(es: Seq[Throwable]): Seq[xsbti.Problem] = {
    es flatMap {
      case cf: xsbti.CompileFailed => cf.problems
      case _                       => Nil
    }
  }

}

object PlayExceptions {

  private def filterAnnoyingErrorMessages(message: String): String = {
    val overloaded = """(?s)overloaded method value (.*) with alternatives:(.*)cannot be applied to(.*)""".r
    message match {
      case overloaded(method, _, signature) =>
        "Overloaded method value [" + method + "] cannot be applied to " + signature
      case msg => msg
    }
  }

  case class UnexpectedException(message: Option[String] = None, unexpected: Option[Throwable] = None)
      extends PlayException(
        "Unexpected exception",
        message.getOrElse {
          unexpected.map(t => "%s: %s".format(t.getClass.getSimpleName, t.getMessage)).getOrElse("")
        },
        unexpected.orNull
      )

  case class CompilationException(problem: xsbti.Problem)
      extends PlayException.ExceptionSource("Compilation error", filterAnnoyingErrorMessages(problem.message)) {
    def line       = problem.position.line.map(m => m.asInstanceOf[java.lang.Integer]).orNull
    def position   = problem.position.pointer.map(m => m.asInstanceOf[java.lang.Integer]).orNull
    def input      = problem.position.sourceFile.map(IO.read(_)).orNull
    def sourceName = problem.position.sourceFile.map(_.getAbsolutePath).orNull
  }

}
