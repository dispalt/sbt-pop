/*
 * Copyright (C) 2017 Dan Di Spaltro
 */
package com.dispalt.fwatch.sbt

import java.io.{ Closeable, File }
import java.net.URL
import java.nio.file.Path
import java.security.{ AccessController, PrivilegedAction }
import java.time.Instant
import java.util
import java.util.{ Timer, TimerTask }
import java.util.concurrent.{ Executors, LinkedBlockingQueue }
import java.util.concurrent.atomic.AtomicReference

import jline.console.ConsoleReader
import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin
import com.dispalt.fwatch.core.FastWatchVersion
import com.dispalt.fwatch.sbt.Reloader.DevServer
import sbt.Def.Initialize

import scala.concurrent.{ Await, ExecutionContext }
import scala.util.control.NonFatal

object FastWatch extends AutoPlugin {

  override def requires = JvmPlugin

  override def trigger = noTrigger

  object autoImport {
    lazy val runDevelop = taskKey[(String, DevServer)](
      "The main command to run at the command line to start watching the files in each project and react appropriately."
    )

    lazy val fwReloaderClasspath = taskKey[Classpath]("todo")

    lazy val fwClassLoaderDecorator = taskKey[ClassLoader => ClassLoader](
      "Function that decorates the Lagom classloader. Can be used to inject things into the classpath."
    )

    /**
      * Don't really mess with this, it's internal
      */
    lazy val fwMonitoredProjectDirs = taskKey[Seq[(ProjectRef, Seq[File])]]("Pair the projects with the files.")

    /**
      * Set the watched projects to things you care about, it defaults to compiling on change.
      */
    lazy val fwWatchedProjects =
      taskKey[Seq[(ProjectRef, TaskKey[_])]]("Watch these projects, execute these tasks when they change.")

    /**
      * Not used for now...
      */
    lazy val fwCompileEverything =
      taskKey[sbt.inc.Analysis]("Compiles this project and every project it depends on.")

    /**
      * This will most likely be able to be implemented differently.
      */
    lazy val fwWatcherService = taskKey[JDK7FileWatchService]("JDK7 File watcher singleton.")

    /**
      * All the watchers that are connected to the main watcher.
      */
    lazy val fwStartWatchers = taskKey[Seq[FileWatcher]]("File watcher")

    /**
      * Override this if you need to do something at the beginning
      */
    lazy val fwStartHook = taskKey[Unit]("Start hook")

    /**
      * Override this if you need to stop
      */
    lazy val fwStopHook = taskKey[Unit]("Stop hook")
  }

  import autoImport._

  override def projectSettings = Seq(
    /**
      * Set the initial projects.
      */
    fwStartHook := {},
    fwStopHook := {},
    fwClassLoaderDecorator := identity,
    fwReloaderClasspath := Classpaths
      .concatDistinct(exportedProducts in Runtime, internalDependencyClasspath in Runtime)
      .value,
    Keys.run in Compile := {
      val service = runDevelop.value
      val log     = state.value.log
      SbtConsoleHelper.printStartScreen(log, service)
      SbtConsoleHelper.blockUntilExit(log, service._2)
    },
    mainClass in Keys.run := None,
    runDevelop := {
      val service = runDevelopTask.value
      service.reload()
      service.addChangeListener(() => service.reload())
      (name.value, service)
    },
    fwWatchedProjects := Seq((thisProjectRef.value, compile in Compile)),
    fwWatcherService := new JDK7FileWatchService(streams.value.log),
    fwCompileEverything := Def.taskDyn {
      val compileTask = compile in Compile
      val watched     = fwWatchedProjects.value
      val sf = watched
        .map { p =>
          ScopeFilter(
            inDependencies(p._1)
          )
        }
        .reduce(_ || _)

      compileTask.all(sf).map(_.reduceLeft(_ ++ _))
    }.value,
    // Monitored Dirs
    // Copied from PlayCommands.scala
    fwMonitoredProjectDirs := Def.taskDyn {

      fwWatchedProjects.value
        .map(_._1)
        .map { p =>
          filteredDirs(projectFilter(p)).map(lf => (p, lf))
        }
        .joinWith(_.join)

    }.value,
    fwStartWatchers := Def.taskDyn {
      val wp      = zipTogether(fwWatchedProjects.value, fwMonitoredProjectDirs.value)
      val watcher = fwWatcherService.value
      val log     = streams.value.log

      wp.map { w =>
          watchAndRun(watcher, w._3, w._2, w._1, log)
        }
        .joinWith(_.join)
    }.value,
    ivyConfigurations ++= Seq(Internal.Configs.DevRuntime),
    manageClasspath(Internal.Configs.DevRuntime),
    libraryDependencies ++= Seq(
      "com.dispalt.fwatch" %% "fw-reloadable-server" % FastWatchVersion.current % Internal.Configs.DevRuntime,
      // TODO: Not sure I should really do this.
      "com.dispalt.fwatch" % "build-link" % FastWatchVersion.current
    )
  )

  private lazy val runDevelopTask: Initialize[Task[DevServer]] = Def.taskDyn {
    RunSupport.reloadRunTask(Map.empty)
  }

  private def manageClasspath(config: Configuration) =
    managedClasspath in config := {
      val ct     = (classpathTypes in config).value
      val report = update.value
      Classpaths.managedJars(config, ct, report)
    }

  private def zipTogether(watchedProjects: Seq[(ProjectRef, TaskKey[_])],
                          monitoredProjectDirs: Seq[(ProjectRef, Seq[File])]) = {
    watchedProjects.zip(monitoredProjectDirs).map {
      case ((p, tk), (_, files)) => (p, tk, files)
    }
  }

  private def watchAndRun(
      watcher: JDK7FileWatchService,
      files: Seq[File],
      task: TaskKey[_],
      p: ProjectRef,
      log: Logger
  ) =
    Def.task {
      val state = Keys.state.value

      watcher.watch(files, { () =>
        log.info(s"'${p.project}' changed, running ${task.key}")
        val Some((_, f)) = Project.runTask(task in p, state)
      })

    }

  private def projectFilter(projectRef: ProjectRef) = ScopeFilter(
    inDependencies(projectRef),
    inConfigurations(Compile)
  )

  private def filteredDirs(filter: ScopeFilter): Def.Initialize[Task[List[File]]] = Def.task {
    val allDirectories: Seq[File] =
      (unmanagedSourceDirectories ?? Nil).all(filter).value.flatten ++
        (unmanagedResourceDirectories ?? Nil).all(filter).value.flatten

    val existingDirectories = allDirectories.filter(_.exists)

    // Filter out directories that are sub paths of each other, by sorting them
    // lexicographically, then folding, excluding entries if the previous entry is a sub path of the current
    val distinctDirectories = existingDirectories
      .map(_.getCanonicalFile.toPath)
      .sorted
      .foldLeft(List.empty[Path]) { (result, next) =>
        result.headOption match {
          case Some(previous) if next.startsWith(previous) => result
          case _                                           => next :: result
        }
      }

    distinctDirectories.map(_.toFile)
  }
}

trait FileWatcher {

  /**
    * Do the initial change run, so you can force it.
    */
  def runChange(): Unit

  /**
    * Stop watching the files.
    */
  def stop(): Unit
}

class JDK7FileWatchService(logger: Logger) {

  import java.nio.file._
  import StandardWatchEventKinds._

  def watch(filesToWatch: Seq[File], onChange: () => Unit): FileWatcher = {
    val dirsToWatch = filesToWatch.filter { file =>
      if (file.isDirectory) {
        true
      } else if (file.isFile) {
        // JDK7 WatchService can't watch files
        logger.warn(
          "JDK7 WatchService only supports watching directories, but an attempt has been made to watch the file: " + file.getCanonicalPath
        )
        logger.warn(
          "This file will not be watched. Either remove the file from playMonitoredFiles, or configure a different WatchService, eg:"
        )
        logger.warn("PlayKeys.fileWatchService := play.runsupport.FileWatchService.jnotify(target.value)")
        false
      } else false
    }

    val watcher = FileSystems.getDefault.newWatchService()

    def watchDir(dir: File) = {
      dir.toPath.register(
        watcher,
        Array[WatchEvent.Kind[_]](ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY),
        // This custom modifier exists just for polling implementations of the watch service, and means poll every 2 seconds.
        // For non polling event based watchers, it has no effect.
        com.sun.nio.file.SensitivityWatchEventModifier.HIGH
      )
    }

    // Get all sub directories
    val allDirsToWatch = allSubDirectories(dirsToWatch)
    allDirsToWatch.foreach(watchDir)

    val thread = new Thread(
      new Runnable {
        def run() = {
          try {
            while (true) {
              val watchKey = watcher.take()

              val events = watchKey.pollEvents()

              import scala.collection.JavaConversions._
              // If a directory has been created, we must watch it and its sub directories
              events.foreach { event =>
                if (event.kind == ENTRY_CREATE) {
                  val file = watchKey.watchable.asInstanceOf[Path].resolve(event.context.asInstanceOf[Path]).toFile

                  if (file.isDirectory) {
                    allSubDirectories(Seq(file)).foreach(watchDir)
                  }
                }
              }

              onChange()

              watchKey.reset()
            }
          } catch {
            case NonFatal(e) => // Do nothing, this means the watch service has been closed, or we've been interrupted.
          } finally {
            // Just in case it wasn't closed.
            watcher.close()
          }
        }
      },
      "sbt-watcher-watch-service"
    )
    thread.setDaemon(true)
    thread.start()

    new FileWatcher {
      def runChange() = {
        onChange()
      }

      def stop() = {
        watcher.close()
      }
    }

  }

  private def allSubDirectories(dirs: Seq[File]) = {
    (dirs ** (DirectoryFilter -- HiddenFileFilter)).get.distinct
  }
}

private[sbt] object SbtConsoleHelper {
  private val consoleHelper = new ConsoleHelper(new Colors("sbt.log.noformat"))
  def printStartScreen(log: Logger, services: (String, DevServer)*): Unit =
    consoleHelper.printStartScreen(log, services.map {
      case (name, service) => name -> service.url()
    })

  def blockUntilExit(log: Logger, services: Closeable*): Unit = {
    consoleHelper.blockUntilExit()
    consoleHelper.shutdownAsynchronously(log, services)
  }
}
