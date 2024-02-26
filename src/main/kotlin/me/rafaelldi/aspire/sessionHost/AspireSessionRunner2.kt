package me.rafaelldi.aspire.sessionHost

import com.intellij.execution.CantRunException
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.*
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.rd.util.withUiContext
import com.intellij.openapi.util.Key
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.io.systemIndependentPath
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.isNotAlive
import com.jetbrains.rider.debugger.DebuggerWorkerProcessHandler
import com.jetbrains.rider.model.RunnableProject
import com.jetbrains.rider.model.runnableProjectsModel
import com.jetbrains.rider.projectView.solution
import com.jetbrains.rider.run.TerminalProcessHandler
import com.jetbrains.rider.run.configurations.project.DotNetProjectConfiguration
import com.jetbrains.rider.run.configurations.project.DotNetProjectConfigurationType
import com.jetbrains.rider.run.environment.ExecutableParameterProcessingResult
import com.jetbrains.rider.run.environment.ExecutableParameterProcessor
import com.jetbrains.rider.run.environment.ExecutableRunParameters
import com.jetbrains.rider.run.environment.ProjectProcessOptions
import com.jetbrains.rider.run.pid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import me.rafaelldi.aspire.generated.SessionModel
import me.rafaelldi.aspire.settings.AspireSettings
import me.rafaelldi.aspire.util.decodeAnsiCommandsToString
import org.jetbrains.concurrency.await
import java.io.File
import kotlin.io.path.Path

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

        val configuration = getOrCreateConfiguration(sessionModel, hostName, openTelemetryPort)
        if (configuration == null) {
            LOG.warn("Unable to find or create run configuration for the project ${sessionModel.projectPath}")
            return
        }

        val isDebug = isHostDebug || sessionModel.debug
        val executor =
            if (!isDebug) DefaultRunExecutor.getRunExecutorInstance()
            else DefaultDebugExecutor.getDebugExecutorInstance()

        val environment = ExecutionEnvironmentBuilder
            .create(project, executor, configuration.configuration)
            .build()

        val started = CompletableDeferred<Boolean>()
        withUiContext {
            ProgramRunnerUtil.executeConfigurationAsync(environment, false, true, object : ProgramRunner.Callback {
                override fun processStarted(descriptor: RunContentDescriptor?) {
                    LOG.info("Aspire session process started")

                    descriptor?.apply {
                        isActivateToolWindowWhenAdded = false
                        isAutoFocusContent = false
                    }

                    val processHandler = getProcessHandler(descriptor?.processHandler)
                    if (processHandler != null) {
                        val pid = processHandler.pid()
                        LOG.trace("Aspire session pid: $pid")
                        if (pid != null) {
                            sessionEvents.trySend(AspireSessionStarted(sessionId, pid))
                        }

                        val processAdapter = object : ProcessAdapter() {
                            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                                val text = decodeAnsiCommandsToString(event.text, outputType)
                                val isStdErr = outputType == ProcessOutputType.STDERR
                                sessionEvents.trySend(AspireSessionLogReceived(sessionId, isStdErr, text))
                            }

                            override fun processTerminated(event: ProcessEvent) {
                                LOG.info("Aspire session process terminated (pid: $pid)")
                                sessionEvents.trySend(AspireSessionTerminated(sessionId, event.exitCode))
                            }

                            override fun processNotStarted() {
                                LOG.warn("Aspire session process is not started")
                                sessionEvents.trySend(AspireSessionTerminated(sessionId, -1))
                            }
                        }

                        sessionLifetime.onTermination {
                            if (!processHandler.isProcessTerminating && !processHandler.isProcessTerminated) {
                                LOG.trace("Killing session process (pid: $pid)")
                                processHandler.destroyProcess()
                            }
                        }

                        processHandler.addProcessListener(processAdapter)
                    }

                    started.complete(true)
                }

                override fun processNotStarted() {
                    started.complete(false)
                }
            })
        }

        if (!started.await()) {
            LOG.warn("Unable to start run configuration for the project ${sessionModel.projectPath}")
        }
    }

    private fun getProcessHandler(processHandler: ProcessHandler?) = when (processHandler) {
        is TerminalProcessHandler -> processHandler
        is DebuggerWorkerProcessHandler -> processHandler.debuggerWorkerRealHandler
        else -> {
            LOG.warn("Unknown ProcessHandler: $processHandler")
            null
        }
    }

    private suspend fun getOrCreateConfiguration(
        session: SessionModel,
        hostName: String,
        openTelemetryPort: Int
    ): RunnerAndConfigurationSettings? {
        val projects = project.solution.runnableProjectsModel.projects.valueOrNull
        if (projects == null) {
            LOG.warn("Runnable projects model doesn't contain projects")
            return null
        }

        val projectPath = Path(session.projectPath).systemIndependentPath
        LOG.trace("Session project path: $projectPath")
        val runnableProject = projects.firstOrNull {
            DotNetProjectConfigurationType.isTypeApplicable(it.kind) && it.projectFilePath == projectPath
        }
        if (runnableProject == null) {
            LOG.warn("Unable to find a specified runnable project")
            return null
        }

        val runManager = RunManager.getInstance(project)
        val configurationType = ConfigurationTypeUtil.findConfigurationType(DotNetProjectConfigurationType::class.java)
        val configurationName = "${runnableProject.name}-$ASPIRE_SUFFIX"
        LOG.trace("Session run configuration name: $configurationName")
        val existingConfiguration = runManager.allSettings.firstOrNull {
            it.type.id == configurationType.id && it.name == configurationName && it.folderName == hostName
        }

        if (existingConfiguration != null) {
            LOG.info("Found existing run configuration ${existingConfiguration.name}")
            return updateConfiguration(
                existingConfiguration,
                runnableProject,
                session,
                openTelemetryPort
            )
        } else {
            LOG.info("Creating a new run configuration $configurationName")
            return createConfiguration(
                configurationType,
                configurationName,
                runManager,
                runnableProject,
                session,
                hostName,
                openTelemetryPort
            )
        }
    }

    private suspend fun updateConfiguration(
        existingConfiguration: RunnerAndConfigurationSettings,
        runnableProject: RunnableProject,
        session: SessionModel,
        openTelemetryPort: Int
    ): RunnerAndConfigurationSettings {
        val runParams = getRunParameters(runnableProject, session, openTelemetryPort)
        existingConfiguration.apply {
            (configuration as DotNetProjectConfiguration).apply {
                parameters.projectFilePath = runnableProject.projectFilePath
                parameters.projectKind = runnableProject.kind
                parameters.programParameters = runParams.commandLineArgumentString ?: ParametersListUtil.join(
                    session.args?.toList() ?: emptyList()
                )
                parameters.envs = runParams.environmentVariables
            }
        }

        LOG.trace("Updated a run configuration $existingConfiguration")

        return existingConfiguration
    }

    private suspend fun createConfiguration(
        configurationType: DotNetProjectConfigurationType,
        configurationName: String,
        runManager: RunManager,
        runnableProject: RunnableProject,
        session: SessionModel,
        hostName: String,
        openTelemetryPort: Int
    ): RunnerAndConfigurationSettings {
        val runParams = getRunParameters(runnableProject, session, openTelemetryPort)
        val factory = configurationType.factory
        val defaultConfiguration =
            runManager.createConfiguration(configurationName, factory).apply {
                (configuration as DotNetProjectConfiguration).apply {
                    parameters.projectFilePath = runnableProject.projectFilePath
                    parameters.projectKind = runnableProject.kind
                    parameters.programParameters = runParams.commandLineArgumentString ?: ParametersListUtil.join(
                        session.args?.toList() ?: emptyList()
                    )
                    parameters.envs = runParams.environmentVariables
                }
                isActivateToolWindowBeforeRun = false
                isFocusToolWindowBeforeRun = false
                folderName = hostName
            }

        runManager.addConfiguration(defaultConfiguration)

        LOG.trace("Created a run configuration $defaultConfiguration")

        return defaultConfiguration
    }

    private suspend fun getRunParameters(
        runnableProject: RunnableProject,
        session: SessionModel,
        openTelemetryPort: Int
    ): ExecutableParameterProcessingResult {
        val projectOutput = runnableProject.projectOutputs.firstOrNull()
            ?: throw CantRunException("Unable to find project output")
        val processOptions = ProjectProcessOptions(
            File(runnableProject.projectFilePath),
            File(projectOutput.workingDirectory)
        )
        val envs = session.envs?.associate { it.key to it.value }?.toMutableMap() ?: mutableMapOf()
        if (AspireSettings.getInstance().collectTelemetry)
            envs[OTEL_EXPORTER_OTLP_ENDPOINT] = "https://localhost:$openTelemetryPort"
        val runParameters = ExecutableRunParameters(
            projectOutput.exePath,
            projectOutput.workingDirectory,
            ParametersListUtil.join(projectOutput.defaultArguments),
            envs,
            true,
            projectOutput.tfm
        )

        val params = ExecutableParameterProcessor
            .getInstance(project)
            .processEnvironment(runParameters, processOptions)
            .await()

        return params
    }
}