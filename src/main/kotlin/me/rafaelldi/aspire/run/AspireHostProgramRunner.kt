package me.rafaelldi.aspire.run

import com.intellij.execution.CantRunException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.showRunContent
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.rd.util.lifetime
import com.intellij.openapi.rd.util.startOnUiAsync
import com.jetbrains.rd.framework.*
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rdclient.protocol.RdDispatcher
import com.jetbrains.rider.debugger.DotNetProgramRunner
import com.jetbrains.rider.run.DotNetProcessRunProfileState
import com.jetbrains.rider.util.NetUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rafaelldi.aspire.generated.aspireSessionHostModel
import me.rafaelldi.aspire.services.AspireServiceManager
import me.rafaelldi.aspire.sessionHost.AspireSessionHostManager
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.asPromise
import kotlin.io.path.Path

class AspireHostProgramRunner : DotNetProgramRunner() {
    companion object {
        const val DEBUG_SESSION_TOKEN = "DEBUG_SESSION_TOKEN"
        const val DEBUG_SESSION_PORT = "DEBUG_SESSION_PORT"
        const val DOTNET_RESOURCE_SERVICE_ENDPOINT_URL = "DOTNET_RESOURCE_SERVICE_ENDPOINT_URL"
        const val DOTNET_DASHBOARD_OTLP_ENDPOINT_URL = "DOTNET_DASHBOARD_OTLP_ENDPOINT_URL"
        private const val RUNNER_ID = "aspire-runner"

        private val LOG = logger<AspireHostProgramRunner>()
    }

    override fun getRunnerId() = RUNNER_ID

    override fun canRun(executorId: String, runConfiguration: RunProfile) = runConfiguration is AspireHostConfiguration

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        LOG.trace("Executing Aspire run profile state")

        val dotnetProcessState = state as? DotNetProcessRunProfileState
            ?: throw CantRunException("Unable to execute RunProfileState: $state")

        val environmentVariables = dotnetProcessState.dotNetExecutable.environmentVariables
        val debugSessionToken = environmentVariables[DEBUG_SESSION_TOKEN]
        val debugSessionPort = environmentVariables[DEBUG_SESSION_PORT]
            ?.substringAfter(':')
            ?.toInt()
        if (debugSessionToken == null || debugSessionPort == null)
            throw CantRunException("Unable to find token or port")
        LOG.trace("Found $DEBUG_SESSION_TOKEN $debugSessionToken and $DEBUG_SESSION_PORT $debugSessionPort")

        val resourceServiceUrl = environmentVariables[DOTNET_RESOURCE_SERVICE_ENDPOINT_URL]
        LOG.trace("Found $DOTNET_RESOURCE_SERVICE_ENDPOINT_URL $resourceServiceUrl")

        val openTelemetryProtocolUrl = environmentVariables[DOTNET_DASHBOARD_OTLP_ENDPOINT_URL]
        LOG.trace("Found $DOTNET_DASHBOARD_OTLP_ENDPOINT_URL $openTelemetryProtocolUrl")

        val isDebug = environment.executor.id == DefaultDebugExecutor.EXECUTOR_ID

        val parameters =
            (environment.runnerAndConfigurationSettings?.configuration as? AspireHostConfiguration)?.parameters
                ?: throw CantRunException("Unable to find AspireHostConfiguration parameters")
        val aspireHostProjectPath = Path(parameters.projectFilePath)
        val aspireHostProjectUrl = parameters.startBrowserParameters.url

        val aspireHostLifetime = environment.project.lifetime.createNested()

        val openTelemetryProtocolServerPort = NetUtils.findFreePort(77800)
        val config = AspireHostProjectConfig(
            debugSessionToken,
            debugSessionPort,
            aspireHostProjectPath,
            aspireHostProjectUrl,
            isDebug,
            resourceServiceUrl,
            openTelemetryProtocolUrl,
            openTelemetryProtocolServerPort
        )
        LOG.trace("Aspire session host config: $config")

        val sessionHostPromise = aspireHostLifetime.startOnUiAsync {
            val protocol = startProtocol(aspireHostLifetime)
            val sessionHostModel = protocol.aspireSessionHostModel

            AspireServiceManager.getInstance(environment.project).startAspireHostService(
                config,
                sessionHostModel,
                aspireHostLifetime.createNested()
            )

            AspireSessionHostManager.getInstance(environment.project).runSessionHost(
                config,
                protocol.wire.serverPort,
                sessionHostModel,
                aspireHostLifetime.createNested()
            )
        }.asPromise()

        return sessionHostPromise.then {
            val executionResult = state.execute(environment.executor, this)

            AspireServiceManager.getInstance(environment.project)
                .updateAspireHostService(config.aspireHostProjectPath, executionResult)

            val processHandler = executionResult.processHandler
            aspireHostLifetime.onTermination {
                LOG.trace("Aspire host lifetime is terminated")
                if (!processHandler.isProcessTerminating && !processHandler.isProcessTerminated) {
                    processHandler.destroyProcess()
                }
            }
            processHandler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    LOG.trace("Aspire host process is terminated")
                    aspireHostLifetime.executeIfAlive {
                        aspireHostLifetime.terminate(true)
                    }
                }
            })

            return@then showRunContent(executionResult, environment)
        }
    }

    private suspend fun startProtocol(lifetime: Lifetime) = withContext(Dispatchers.EDT) {
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
        return@withContext protocol
    }
}