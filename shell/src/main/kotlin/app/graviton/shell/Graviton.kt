@file:JvmName("Graviton")
package app.graviton.shell

import app.graviton.scheduler.OSScheduledTaskDefinition
import app.graviton.scheduler.OSTaskScheduler
import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.effect.Effect
import javafx.scene.image.Image
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import picocli.CommandLine
import tornadofx.Component
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import kotlin.concurrent.thread

//
// The main method, argument parsing, first run checks, last run handling (uninstall), console setup.
//

//region Global variables
/** The installed path of the browser, when packaged as a native installer. */
val gravitonPath: String? = System.getenv("GRAVITON_PATH")

/** The current version, as discovered by the bootstrapper. */
val gravitonVersion: Int? = System.getenv("GRAVITON_VERSION")?.toInt()

/** The top level logger for the app. */
val mainLog: Logger by lazy { LoggerFactory.getLogger("main") }

/** Global access to parsed command line flags. */
var commandLineArguments = GravitonCLI(arrayOf(""))

/** Object that loads and manages the user's history list. */
val historyManager: HistoryManager by lazy { HistoryManager.create() }

/** Controls whether the spinner animation is active or not. */
val isWorking by lazy { SimpleBooleanProperty() }

// Allow for minimal rebranding in future.
const val appBrandName = "Graviton"
/** Should the name be put next to the logo, or, should we just use the logo alone (i.e. it is the name) */
const val appBrandLogoIsName = false
/** If set, an effect to apply to the logo image. */
val appLogoEffect: Effect? = null
/** The logo image to use on the UI;. */
val Component.appBrandLogo get() = Image(resources["art/icons8-rocket-take-off-128.png"])
//endregion

fun main(arguments: Array<String>) {
    try {
        main1(arguments)
    } catch (e: Throwable) {
        try {
            mainLog.error("Failed to start up", e)
            e.printStackTrace()
            if (currentOperatingSystem == OperatingSystem.WIN) {
                windowsAlertBox("Failed to start up", e.asString())
            }
        } catch (e: Throwable) {
            // Just not our day today.....
        }
    }
}

private fun main1(arguments: Array<String>) {
    // The shell may request that we just immediately run a program with a provided classpath, as part of starting
    // up a non-Graviton app outside the shell process.
    if (immediatelyInvokeApplication(arguments))
        return

    var forceAnsi = false
    if (currentOperatingSystem == OperatingSystem.WIN) {
        // Windows has managed to screw up its console handling really, really badly. We need some hacks to
        // make command line apps and GUI apps work from the same (ish) binary. See the attachToParentConsole
        // function for the gory details.
        forceAnsi = attachToParentConsole()
    }

    commandLineArguments = GravitonCLI(arguments)
    val cli = CommandLine(commandLineArguments)
    cli.isStopAtPositional = true
    cli.usageHelpWidth = if (arguments.isNotEmpty()) getTermWidth() else 80  // Don't care

    // Force ANSI on because we enable it on Windows 10 now.
    val handler = CommandLine.RunLast()
    if (forceAnsi)
        handler.useAnsi(CommandLine.Help.Ansi.ON)

    // This call will pass control to GravitonCLI.run (as that's the object in commandLineArguments).
    cli.parseWithHandlers(handler, CommandLine.DefaultExceptionHandler<List<Any>>(), *arguments)
}

private fun immediatelyInvokeApplication(arguments: Array<String>): Boolean {
    // This is only called when an app is invoked from the Graviton GUI, so we don't care about ANSI or console stuff here.
    // Use environment variables to allow us to keep the arguments list clean, and to stop anyone from being able to
    // divert us onto this codepath in case of URL handler bugs (URLs cannot set environment variables).
    val runCP: String = System.getenv("GRAVITON_RUN_CP") ?: return false
    val runClassName: String = System.getenv("GRAVITON_RUN_CLASSNAME") ?: return false
    val cl = GravitonClassLoader.build(runCP)
    val clazz = cl.loadClass(runClassName)
    // This thread will kick off and start running the program. It won't be able to see Graviton's classes because it's
    // in a separate classloader that doesn't chain to the one that loaded us. This isn't perfectly compatible (a few
    // big/complex apps expect the classloader to be a sun.misc.AppClassLoader) but it'll do for now. This thread will
    // continue, die, and the new thread will be the only one left. Eventually the GC should clear out the code in
    // this file from memory, and the new app will have a relatively clean stack trace.
    thread(name = "main", contextClassLoader = cl) {
        runMain(clazz, arguments)
    }
    return true
}

private fun getTermWidth(): Int {
    return try {
        when (currentOperatingSystem) {
            OperatingSystem.MAC, OperatingSystem.LINUX -> {
                val proc = ProcessBuilder("stty", "size").redirectInput(ProcessBuilder.Redirect.INHERIT).start()
                proc.waitFor()
                val o2 = String(proc.inputStream.readBytes())
                val output = o2.split(' ')[1].trim()
                output.toInt()
            }
            else -> 80
        }
    } catch (t: Throwable) {
        80
    }
}

fun startupChecks(myPath: String, myVersion: Int) {
    // Do it in the background to keep the slow file IO away from blocking startup.
    thread(start = true) {
        try {
            val appPath: Path = Paths.get(myPath)
            val versionPath = appPath / "last-run-version"
            val taskSchedulerErrorFile = appPath / "task-scheduler-error-log.txt"
            if (!versionPath.exists || taskSchedulerErrorFile.exists)
                firstRun(appPath, taskSchedulerErrorFile)
            Files.write(versionPath, listOf("$myVersion"))
        } catch (e: Exception) {
            // Log but don't block startup.
            mainLog.error("Failed to do background startup checks", e)
        }
    }
}

private const val taskName = "app.graviton.update"

private fun firstRun(myPath: Path, taskSchedulerErrorFile: Path) {
    mainLog.info("First run, attempting to register scheduled task")
    val scheduler: OSTaskScheduler? = OSTaskScheduler.get()
    if (scheduler == null) {
        mainLog.info("No support for task scheduling on this OS: $currentOperatingSystem")
        return
    }
    val executePath = when (currentOperatingSystem) {
        OperatingSystem.MAC -> myPath / "MacOS" / "Graviton Browser"
        OperatingSystem.WIN -> myPath / "GravitonBrowser.exe"
        OperatingSystem.LINUX -> myPath / "GravitonBrowser"
        OperatingSystem.UNKNOWN -> return
    }
    // Poll the server four times a day. This is a pretty aggressive interval but is useful in the project's early
    // life where I want to be able to update things quickly and users may be impatient.
    val scheduledTask = OSScheduledTaskDefinition(
            executePath = executePath,
            arguments = listOf("--background-update"),
            frequency = when (currentOperatingSystem) {
                // I couldn't make the Windows task scheduler do non-integral numbers of days, see WindowsTaskScheduler.kt
                OperatingSystem.WIN -> Duration.ofHours(24)
                else -> Duration.ofHours(6)
            },
            description = "Graviton background upgrade task. If you disable this, Graviton Browser may become insecure.",
            networkSensitive = true
    )
    try {
        Files.deleteIfExists(taskSchedulerErrorFile)
        scheduler.register(taskName, scheduledTask)
        mainLog.info("Registered background task successfully with name '$taskName'")
    } catch (e: Exception) {
        // If we failed to register the task we will store the error to a dedicated file, which will act
        // as a marker to retry next time.
        taskSchedulerErrorFile.toFile().writer().use {
            e.printStackTrace(PrintWriter(it))
        }
        mainLog.error("Failed to register background task", e)
    }
}

fun lastRun() {
    mainLog.info("Uninstallation requested, removing scheduled task")
    try {
        val scheduler: OSTaskScheduler? = OSTaskScheduler.get()
        if (scheduler == null) {
            mainLog.info("No support for task scheduling on this OS: $currentOperatingSystem")
            return
        }
        scheduler.deregister(taskName)
    } catch (e: Throwable) {
        // Don't want to spam the user with errors.
        mainLog.error("Exception during uninstall", e)
    }
}