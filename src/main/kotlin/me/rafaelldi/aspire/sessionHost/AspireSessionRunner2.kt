package me.rafaelldi.aspire.sessionHost

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.io.systemIndependentPath
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.isNotAlive
import com.jetbrains.rider.RiderEnvironment.createRunCmdForLauncherInfo
import com.jetbrains.rider.debugger.NotifiableDebuggerWorkerProcessHandler
import com.jetbrains.rider.debugger.attach.RiderDebuggerWorkerConnector
import com.jetbrains.rider.debugger.targets.DEBUGGER_WORKER_LAUNCHER
import com.jetbrains.rider.model.RdTargetFrameworkId
import com.jetbrains.rider.model.debuggerWorker.*
import com.jetbrains.rider.model.runnableProjectsModel
import com.jetbrains.rider.projectView.solution
import com.jetbrains.rider.run.ConsoleKind
import com.jetbrains.rider.run.FormatPreservingCommandLine
import com.jetbrains.rider.run.configurations.RunnableProjectKinds
import com.jetbrains.rider.run.createConsole
import com.jetbrains.rider.run.pid
import com.jetbrains.rider.runtime.RiderDotNetActiveRuntimeHost
import com.jetbrains.rider.util.NetUtils
import icons.RiderIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rafaelldi.aspire.generated.SessionModel
import me.rafaelldi.aspire.settings.AspireSettings
import me.rafaelldi.aspire.util.MSBuildPropertyService
import me.rafaelldi.aspire.util.decodeAnsiCommandsToString
import java.io.OutputStream
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.nameWithoutExtension

@Service(Service.Level.PROJECT)
class AspireSessionRunner2(private val project: Project, scope: CoroutineScope) {
    companion object {
        fun getInstance(project: Project) = project.service<AspireSessionRunner2>()

        private val LOG = logger<AspireSessionRunner2>()

        private const val OTEL_EXPORTER_OTLP_ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT"
        private fun getOtlpEndpoint(port: Int) = "http://localhost:$port"
    }

    private val commandChannel = Channel<AspireSessionRunner.RunSessionCommand>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.Default) {
            commandChannel.consumeAsFlow().collect {
                runSession(
                    it.sessionId,
                    it.sessionModel,
                    it.sessionLifetime,
                    it.sessionEvents,
                    it.isHostDebug,
                    it.openTelemetryPort
                )
            }
        }
    }

    fun runSession(command: AspireSessionRunner.RunSessionCommand) {
        LOG.trace("Sending run session command $command")
        commandChannel.trySend(command)
    }

    private suspend fun runSession(
        sessionId: String,
        sessionModel: SessionModel,
        sessionLifetime: Lifetime,
        sessionEvents: Channel<AspireSessionEvent>,
        isHostDebug: Boolean,
        openTelemetryPort: Int
    ) {
        LOG.info("Starting a session for the project ${sessionModel.projectPath}")

        if (sessionLifetime.isNotAlive) {
            LOG.warn("Unable to run project ${sessionModel.projectPath} because lifetimes are not alive")
            return
        }

        val executable = getExecutable(sessionModel)
        if (executable == null) {
            LOG.warn("Unable to find executable for $sessionId (project: ${sessionModel.projectPath})")
            return
        }

        val isDebug = isHostDebug || sessionModel.debug
        if (isDebug) {
            startDebugSession(
                sessionId,
                executable,
                sessionModel,
                sessionLifetime,
                sessionEvents,
                openTelemetryPort
            )
        } else {
            startRunSession(
                sessionId,
                executable.first,
                sessionModel,
                sessionLifetime,
                sessionEvents,
                openTelemetryPort
            )
        }
    }

    private suspend fun getExecutable(sessionModel: SessionModel): Pair<Path, RdTargetFrameworkId?>? {
        val runnableProjects = project.solution.runnableProjectsModel.projects.valueOrNull ?: return null
        val sessionProjectPath = Path(sessionModel.projectPath)
        val sessionProjectPathString = sessionProjectPath.systemIndependentPath
        val runnableProject = runnableProjects.singleOrNull {
            it.projectFilePath == sessionProjectPathString && it.kind == RunnableProjectKinds.DotNetCore
        }
        if (runnableProject != null) {
            val output = runnableProject.projectOutputs.firstOrNull() ?: return null
            return Path(output.exePath) to output.tfm
        } else {
            val propertyService = MSBuildPropertyService.getInstance(project)
            return propertyService.getExecutableFromMSBuildProperties(sessionProjectPath)
        }
    }

    private fun startRunSession(
        sessionId: String,
        executablePath: Path,
        sessionModel: SessionModel,
        sessionLifetime: Lifetime,
        sessionEvents: Channel<AspireSessionEvent>,
        openTelemetryPort: Int
    ) {
        val commandLine = getRunningCommandLine(executablePath, sessionModel, openTelemetryPort)

        val handler = KillableProcessHandler(commandLine)
        handler.addProcessListener(object : ProcessAdapter() {
            override fun startNotified(event: ProcessEvent) {
                LOG.info("Aspire session process started (id: $sessionId)")
                val pid = when (event.processHandler) {
                    is KillableProcessHandler -> event.processHandler.pid()
                    else -> null
                }
                if (pid == null) {
                    LOG.warn("Unable to determine process id for the session $sessionId")
                    sessionEvents.trySend(AspireSessionTerminated(sessionId, -1))
                } else {
                    sessionEvents.trySend(AspireSessionStarted(sessionId, pid))
                }
            }

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = decodeAnsiCommandsToString(event.text, outputType)
                val isStdErr = outputType == ProcessOutputType.STDERR
                sessionEvents.trySend(AspireSessionLogReceived(sessionId, isStdErr, text))
            }

            override fun processTerminated(event: ProcessEvent) {
                LOG.info("Aspire session process terminated (id: $sessionId)")
                sessionEvents.trySend(AspireSessionTerminated(sessionId, event.exitCode))
            }

            override fun processNotStarted() {
                LOG.warn("Aspire session process is not started")
                sessionEvents.trySend(AspireSessionTerminated(sessionId, -1))
            }
        })

        sessionLifetime.onTermination {
            if (!handler.isProcessTerminating && !handler.isProcessTerminated) {
                LOG.trace("Killing session process (id: $sessionId)")
                handler.destroyProcess()
            }
        }

        handler.startNotify()
    }

    private fun getRunningCommandLine(
        executablePath: Path,
        sessionModel: SessionModel,
        openTelemetryPort: Int
    ): GeneralCommandLine {
        val commandLine = FormatPreservingCommandLine()
            .withExePath(executablePath.absolutePathString())
            .withWorkDirectory(executablePath.parent.absolutePathString())

        if (sessionModel.args?.isNotEmpty() == true) {
            commandLine.withParameters(*sessionModel.args)
        }

        if (sessionModel.envs?.isNotEmpty() == true) {
            commandLine.withEnvironment(sessionModel.envs.associate { it.key to it.value })
        }

        if (AspireSettings.getInstance().collectTelemetry) {
            commandLine.withEnvironment(OTEL_EXPORTER_OTLP_ENDPOINT, getOtlpEndpoint(openTelemetryPort))
        }

        return commandLine
    }

    private suspend fun startDebugSession(
        sessionId: String,
        executable: Pair<Path, RdTargetFrameworkId?>,
        sessionModel: SessionModel,
        sessionLifetime: Lifetime,
        sessionEvents: Channel<AspireSessionEvent>,
        openTelemetryPort: Int
    ) {
        val frontendToDebuggerPort = NetUtils.findFreePort(67700)
        val backendToDebuggerPort = NetUtils.findFreePort(87700)

        startDebuggerWorker(sessionId, frontendToDebuggerPort, backendToDebuggerPort, sessionLifetime)

        val runtime = RiderDotNetActiveRuntimeHost.getInstance(project).dotNetCoreRuntime.value
        if (runtime == null) {
            LOG.warn("Unable to find dotnet runtime")
            return
        }

        val (executablePath, projectTfm) = executable
        val args = sessionModel.args?.joinToString(" ") ?: ""
        val envs = sessionModel.envs?.associate { it.key to it.value }?.toMutableMap() ?: mutableMapOf()
        if (AspireSettings.getInstance().collectTelemetry) {
            envs[OTEL_EXPORTER_OTLP_ENDPOINT] = getOtlpEndpoint(openTelemetryPort)
        }

        val startInfo = DotNetCoreExeStartInfo(
            DotNetCoreInfo(runtime.cliExePath),
            projectTfm?.let { EncInfo(it) },
            executablePath.absolutePathString(),
            executablePath.parent.absolutePathString(),
            args,
            envs.map { StringPair(it.key, it.value) }.toList(),
            null,
            true,
            false
        )
        val processHandlerFactory = { workerModel: DebuggerWorkerModel ->
            object : NotifiableDebuggerWorkerProcessHandler(workerModel) {
                override fun detachIsDefault(): Boolean {
                    return true
                }

                override fun getProcessInput(): OutputStream? {
                    return null
                }
            }
        }
        val executionConsoleFactory = { handler: NotifiableDebuggerWorkerProcessHandler ->
            createConsole(ConsoleKind.Normal, handler, project)
        }

        val projectPath = Path(sessionModel.projectPath)
        val connector = RiderDebuggerWorkerConnector.getInstance(project)
        withContext(Dispatchers.Main) {
            connector.startDebugSession(
                frontendToDebuggerPort,
                backendToDebuggerPort,
                ExecutionEnvironment.getNextUnusedExecutionId(),
                projectPath.nameWithoutExtension,
                RiderIcons.RunConfigurations.DotNetProject,
                startInfo,
                processHandlerFactory,
                executionConsoleFactory,
                null,
                sessionLifetime
            )
        }
    }

    private fun startDebuggerWorker(
        sessionId: String,
        frontendToDebuggerPort: Int,
        backendToDebuggerPort: Int,
        sessionLifetime: Lifetime
    ) {
        val launcher = DEBUGGER_WORKER_LAUNCHER.getLauncher()
        val commandLine = createRunCmdForLauncherInfo(
            launcher,
            "--mode=server",
            "--frontend-port=${frontendToDebuggerPort}",
            "--backend-port=${backendToDebuggerPort}"
        )
        val handler = KillableProcessHandler(commandLine)
        sessionLifetime.onTermination {
            if (!handler.isProcessTerminating && !handler.isProcessTerminated) {
                LOG.trace("Killing session process (id: $sessionId)")
                handler.killProcess()
            }
        }
        handler.startNotify()
    }
}