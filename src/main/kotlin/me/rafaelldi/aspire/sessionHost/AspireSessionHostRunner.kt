package me.rafaelldi.aspire.sessionHost

import com.intellij.execution.CantRunException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.rd.createNestedDisposable
import com.intellij.openapi.rd.util.launchOnUi
import com.intellij.openapi.rd.util.withUiContext
import com.intellij.openapi.util.Key
import com.jetbrains.rd.framework.*
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.lifetime.isNotAlive
import com.jetbrains.rdclient.protocol.RdDispatcher
import com.jetbrains.rider.runtime.RiderDotNetActiveRuntimeHost
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import me.rafaelldi.aspire.generated.*
import me.rafaelldi.aspire.util.decodeAnsiCommandsToString
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.div

@Service
class AspireSessionHostRunner {
    companion object {
        fun getInstance() = service<AspireSessionHostRunner>()

        private val LOG = logger<AspireSessionHostRunner>()

        private const val ASPNETCORE_URLS = "ASPNETCORE_URLS"
        private const val RIDER_OTEL_PORT = "RIDER_OTEL_PORT"
        private const val RIDER_PARENT_PROCESS_PID = "RIDER_PARENT_PROCESS_PID"
        private const val RIDER_RD_PORT = "RIDER_RD_PORT"
        private const val DOTNET_OTLP_ENDPOINT_URL = "DOTNET_OTLP_ENDPOINT_URL"
    }

    private val pluginId = PluginId.getId("me.rafaelldi.aspire")

    private val hostAssemblyPath: Path = run {
        val plugin = PluginManagerCore.getPlugin(pluginId) ?: error("Plugin $pluginId could not be found.")
        val basePath = plugin.pluginPath ?: error("Could not detect path of plugin $plugin on disk.")
        basePath / "aspire-session-host" / "aspire-session-host.dll"
    }

    suspend fun runSessionHost(
        project: Project,
        sessionHostConfig: AspireSessionHostConfig,
        sessionHostLifetime: LifetimeDefinition
    ) {
        LOG.info("Starting Aspire session host: $sessionHostConfig")

        if (sessionHostLifetime.isNotAlive) {
            LOG.warn("Unable to start Aspire host because lifetime is not alive")
            return
        }

        val dotnet = RiderDotNetActiveRuntimeHost.getInstance(project).dotNetCoreRuntime.value
            ?: throw CantRunException("Cannot find active .NET runtime")

        val protocol = startProtocol(sessionHostLifetime)
        subscribe(sessionHostConfig, protocol.aspireSessionHostModel, sessionHostLifetime, project)

        val commandLine = GeneralCommandLine()
            .withExePath(dotnet.cliExePath)
            .withCharset(StandardCharsets.UTF_8)
            .withParameters(hostAssemblyPath.toString())
            .withEnvironment(
                buildMap {
                    put(ASPNETCORE_URLS, "http://localhost:${sessionHostConfig.debugSessionPort}/")
                    put(RIDER_OTEL_PORT, sessionHostConfig.openTelemetryPort.toString())
                    put(RIDER_RD_PORT, "${protocol.wire.serverPort}")
                    put(RIDER_PARENT_PROCESS_PID, ProcessHandle.current().pid().toString())
                    if (sessionHostConfig.openTelemetryProtocolUrl != null)
                        put(DOTNET_OTLP_ENDPOINT_URL, sessionHostConfig.openTelemetryProtocolUrl)
                }
            )
        LOG.trace("Host command line: ${commandLine.commandLineString}")
        val processHandler = KillableColoredProcessHandler.Silent(commandLine)
        sessionHostLifetime.onTermination {
            if (!processHandler.isProcessTerminating && !processHandler.isProcessTerminated) {
                LOG.trace("Killing Aspire host process")
                processHandler.killProcess()
            }
        }
        processHandler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = decodeAnsiCommandsToString(event.text, outputType)
                if (outputType == ProcessOutputType.STDERR) {
                    LOG.error(text)
                } else {
                    LOG.debug(text)
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                sessionHostLifetime.executeIfAlive {
                    LOG.trace("Terminating Aspire host lifetime")
                    sessionHostLifetime.terminate(true)
                }
            }
        }, sessionHostLifetime.createNestedDisposable())
        processHandler.startNotify()
        LOG.trace("Aspire session host started")

        project.messageBus.syncPublisher(AspireSessionHostLifecycleListener.TOPIC)
            .sessionHostStarted(sessionHostConfig, protocol.aspireSessionHostModel, sessionHostLifetime)
    }

    private suspend fun startProtocol(lifetime: Lifetime) = withUiContext {
        val dispatcher = RdDispatcher(lifetime)
        val wire = SocketWire.Server(lifetime, dispatcher, null)
        val protocol = Protocol(
            "AspireSessionHost::protocol",
            Serializers(),
            Identities(IdKind.Server),
            dispatcher,
            wire,
            lifetime
        )
        return@withUiContext protocol
    }

    private suspend fun subscribe(
        hostConfig: AspireSessionHostConfig,
        hostModel: AspireSessionHostModel,
        hostLifetime: Lifetime,
        project: Project
    ) {
        val sessionEvents = Channel<AspireSessionEvent>(Channel.UNLIMITED)

        hostLifetime.launchOnUi {
            sessionEvents.consumeAsFlow().collect {
                when (it) {
                    is AspireSessionStarted -> {
                        LOG.trace("Aspire session started (${it.id}, ${it.pid})")
                        hostModel.processStarted.fire(ProcessStarted(it.id, it.pid))
                    }

                    is AspireSessionTerminated -> {
                        LOG.trace("Aspire session terminated (${it.id}, ${it.exitCode})")
                        hostModel.processTerminated.fire(ProcessTerminated(it.id, it.exitCode))
                    }

                    is AspireSessionLogReceived -> {
                        LOG.trace("Aspire session log received (${it.id}, ${it.isStdErr}, ${it.message})")
                        hostModel.logReceived.fire(LogReceived(it.id, it.isStdErr, it.message))
                    }
                }
            }
        }

        withUiContext {
            hostModel.sessions.view(hostLifetime) { sessionLifetime, sessionId, sessionModel ->
                LOG.info("New session added $sessionId, $sessionModel")
                val runner = AspireSessionRunner.getInstance(project)
                runner.runSession(
                    AspireSessionRunner.RunSessionCommand(
                        sessionId,
                        sessionModel,
                        sessionLifetime,
                        sessionEvents,
                        hostConfig.hostName,
                        hostConfig.isDebug,
                        hostConfig.openTelemetryPort
                    )
                )
            }
        }
    }
}