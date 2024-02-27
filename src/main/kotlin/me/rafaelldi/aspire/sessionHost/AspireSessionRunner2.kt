package me.rafaelldi.aspire.sessionHost

import com.intellij.execution.CantRunException
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.rd.util.withBackgroundContext
import com.intellij.openapi.rd.util.withUiContext
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.io.systemIndependentPath
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.isNotAlive
import com.jetbrains.rider.CustomInfo
import com.jetbrains.rider.debugger.*
import com.jetbrains.rider.debugger.attach.RiderDebuggerWorkerConnector
import com.jetbrains.rider.debugger.attach.remoting.CPUKind
import com.jetbrains.rider.debugger.attach.remoting.RemoteDebuggerToolsDownloadHelper
import com.jetbrains.rider.debugger.util.tryGetTargetProcessId
import com.jetbrains.rider.model.RunnableProject
import com.jetbrains.rider.model.debuggerWorker.DebuggerWorkerModel
import com.jetbrains.rider.model.debuggerWorker.DotNetCoreExeStartInfo
import com.jetbrains.rider.model.debuggerWorker.DotNetCoreInfo
import com.jetbrains.rider.model.debuggerWorker.StringPair
import com.jetbrains.rider.model.runnableProjectsModel
import com.jetbrains.rider.projectView.solution
import com.jetbrains.rider.run.*
import com.jetbrains.rider.run.configurations.project.DotNetProjectConfiguration
import com.jetbrains.rider.run.configurations.project.DotNetProjectConfigurationType
import com.jetbrains.rider.run.environment.*
import com.jetbrains.rider.runtime.RiderDotNetActiveRuntimeHost
import com.jetbrains.rider.util.NetUtils
import icons.RiderIcons
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import me.rafaelldi.aspire.generated.SessionModel
import me.rafaelldi.aspire.manifest.ManifestService
import me.rafaelldi.aspire.settings.AspireSettings
import me.rafaelldi.aspire.util.decodeAnsiCommandsToString
import org.jetbrains.concurrency.await
import java.io.File
import java.io.OutputStream
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.nameWithoutExtension

@Service(Service.Level.PROJECT)
class AspireSessionRunner2(private val project: Project, scope: CoroutineScope) {
    companion object {
        fun getInstance(project: Project): AspireSessionRunner2 = project.service()

        private val LOG = logger<AspireSessionRunner2>()

        private const val ASPIRE_SUFFIX = "Aspire"
        private const val OTEL_EXPORTER_OTLP_ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT"
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
                    it.hostName,
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
        hostName: String,
        isHostDebug: Boolean,
        openTelemetryPort: Int
    ) {
        LOG.info("Starting a session for the project ${sessionModel.projectPath}")

        if (sessionLifetime.isNotAlive) {
            LOG.warn("Unable to run project ${sessionModel.projectPath} because lifetimes are not alive")
            return
        }

        val isDebug = isHostDebug || sessionModel.debug
        if (isDebug) {
            startDebugSession(sessionId, sessionModel, sessionLifetime, sessionEvents, openTelemetryPort)
        } else {
            startRunSession(sessionId, sessionModel, sessionLifetime, sessionEvents, openTelemetryPort)
        }
    }

    private fun startRunSession(
        sessionId: String,
        sessionModel: SessionModel,
        sessionLifetime: Lifetime,
        sessionEvents: Channel<AspireSessionEvent>,
        openTelemetryPort: Int
    ) {
        val runtime = RiderDotNetActiveRuntimeHost.getInstance(project).dotNetCoreRuntime.value
        if (runtime == null) {
            LOG.warn("Unable to find .NET runtime")
            return
        }

        val projectPath = Path(sessionModel.projectPath)
        val commandLine = FormatPreservingCommandLine()
            .withExePath(runtime.cliExePath)
            .withParameters("run", "--project", projectPath.absolutePathString())
            .withWorkDirectory(projectPath.parent.absolutePathString())

        commandLine.withParameters("--no-launch-profile", "--no-build")
//        if (sessionModel.disableLaunchProfile) {
//            commandLine.withParameters("--no-launch-profile")
//        } else if (sessionModel.launchProfile != null) {
//            commandLine.withParameters("--launch-profile", sessionModel.launchProfile)
//        }

        if (sessionModel.args?.isNotEmpty() == true) {
            commandLine.withParameters("--")
            commandLine.withParameters(*sessionModel.args)
        }

        if (sessionModel.envs?.isNotEmpty() == true) {
            commandLine.withEnvironment(sessionModel.envs.associate { it.key to it.value })
        }

        if (AspireSettings.getInstance().collectTelemetry){
            commandLine.withEnvironment(OTEL_EXPORTER_OTLP_ENDPOINT, "https://localhost:$openTelemetryPort")
        }

        val handler = KillableProcessHandler(commandLine)
        handler.addProcessListener(object : ProcessAdapter() {
            override fun startNotified(event: ProcessEvent) {
                LOG.info("Aspire session process started (id: $sessionId)")
                val pid = when(event.processHandler) {
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

    private suspend fun startDebugSession(
        sessionId: String,
        sessionModel: SessionModel,
        sessionLifetime: Lifetime,
        sessionEvents: Channel<AspireSessionEvent>,
        openTelemetryPort: Int
    ) {
        val runtime = RiderDotNetActiveRuntimeHost.getInstance(project).dotNetCoreRuntime.value
        if (runtime == null) {
            LOG.warn("Unable to find .NET runtime")
            return
        }

        val frontendToDebuggerPort = NetUtils.findFreePort(67700)
        val backendToDebuggerPort = NetUtils.findFreePort(87700)

        val debuggerToolsDirectory = withBackgroundContext {
            withBackgroundProgress(project, "Downloading debugger tools…") {
                RemoteDebuggerToolsDownloadHelper.downloadDebuggerTools(CPUKind.Win64, project)
            }
        }

        DebugProfileStateBase.createWorkerCmdForWithCustomRunInfo(
            ConsoleKind.ExternalConsole,
            frontendToDebuggerPort,
            DebuggerWorkerPlatform.X64,
            CustomInfo(
                debuggerToolsDirectory
            ),
            ExecutableType.Unknown,
            "--backend-port=$backendToDebuggerPort"
        )

        LOG.info(debuggerToolsDirectory.absolutePath)

        val connector = RiderDebuggerWorkerConnector.getInstance(project)

        val projectPath = Path(sessionModel.projectPath)
        val startInfo = DotNetCoreExeStartInfo(
            DotNetCoreInfo(runtime.cliExePath),
            null,
            runtime.cliExePath,
            projectPath.parent.absolutePathString(),
            "run --project ${projectPath.absolutePathString()} --no-launch-profile --no-build",
            sessionModel.envs?.map { StringPair(it.key, it.value) }?.toList() ?: emptyList(),
            null,
            true,
            false
        )
        val processHandlerFactory = { workerModel: DebuggerWorkerModel ->
            object : NotifiableDebuggerWorkerProcessHandler(workerModel) {
                override fun detachIsDefault(): Boolean {
                    return false
                }

                override fun getProcessInput(): OutputStream? {
                    return null
                }
            }
        }
        val executionConsoleFactory = { _: NotifiableDebuggerWorkerProcessHandler ->
            RiderDebugDisabledExecutionConsole(RiderDebuggerBundle.message("rider.attach.console.kind.message", ToolWindowId.SERVICES))
        }

        withUiContext {
            connector.startDebugSession(
                frontendToDebuggerPort,
                backendToDebuggerPort,
                ExecutionEnvironment.getNextUnusedExecutionId(),
                projectPath.nameWithoutExtension,
                RiderIcons.Debugger.Debugger,
                startInfo,
                processHandlerFactory,
                executionConsoleFactory,
                null,
                sessionLifetime
            )
        }
    }
}