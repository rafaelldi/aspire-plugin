package me.rafaelldi.aspire.sessionHost

import com.intellij.execution.services.ServiceEventListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.rd.util.launchOnUi
import com.intellij.openapi.rd.util.withUiContext
import com.jetbrains.rd.framework.*
import com.jetbrains.rd.framework.util.setSuspend
import com.jetbrains.rd.util.addUnique
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.lifetime.SequentialLifetimes
import com.jetbrains.rd.util.lifetime.isNotAlive
import com.jetbrains.rdclient.protocol.RdDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rafaelldi.aspire.generated.*
import me.rafaelldi.aspire.services.AspireSessionHostServiceContributor
import me.rafaelldi.aspire.services.AspireSessionHostServiceData
import me.rafaelldi.aspire.services.AspireResourceService
import me.rafaelldi.aspire.services.AspireServiceContributor
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class AspireSessionHostManager(private val project: Project) {
    companion object {
        fun getInstance(project: Project) = project.service<AspireSessionHostManager>()

        private val LOG = logger<AspireSessionHostManager>()
    }

    private val mutex = Mutex()

    private val sessionHosts = ConcurrentHashMap<String, AspireSessionHostServiceContributor>()
    private val sessions = ConcurrentHashMap<String, MutableMap<String, Pair<String, SequentialLifetimes>>>()
    private val resources = ConcurrentHashMap<String, MutableMap<String, AspireResourceService>>()

    private val serviceEventPublisher = project.messageBus.syncPublisher(ServiceEventListener.TOPIC)

    fun isSessionHostAvailable(sessionHostId: String) = sessionHosts.containsKey(sessionHostId)
    fun getSessionHost(sessionHostId: String) = sessionHosts[sessionHostId]
    fun getSessionHosts() = sessionHosts.values.toList()

    fun getResources(sessionHostId: String) =
        resources[sessionHostId]?.values?.sortedBy { it.resourceType }?.toList() ?: emptyList()

    suspend fun runSessionHost(
        sessionHostConfig: AspireSessionHostConfig,
        sessionHostLifetime: LifetimeDefinition
    ) {
        LOG.info("Starting Aspire session host: $sessionHostConfig")

        if (sessionHostLifetime.isNotAlive) {
            LOG.warn("Unable to start Aspire host because lifetime is not alive")
            return
        }

        LOG.trace("Starting protocol")
        val protocol = startProtocol(sessionHostLifetime)
        val sessionHostModel = protocol.aspireSessionHostModel

        LOG.trace("Subscribing to protocol model")
        subscribe(sessionHostConfig, sessionHostModel, sessionHostLifetime)

        val sessionHostData = AspireSessionHostServiceData(
            sessionHostConfig.debugSessionToken,
            sessionHostConfig.runProfileName,
            sessionHostConfig.aspireHostProjectPath,
            sessionHostConfig.aspireHostProjectUrl,
            sessionHostModel,
            sessionHostLifetime.lifetime
        )

        sessionHostLifetime.bracketIfAlive({
            addSessionHost(sessionHostData)
        }, {
            removeSessionHost(sessionHostData)
        })

        LOG.trace("Starting new session hosts with runner")
        val sessionHostRunner = AspireSessionHostRunner.getInstance(project)
        sessionHostRunner.runSessionHost(
            sessionHostConfig,
            protocol.wire.serverPort,
            sessionHostLifetime
        )
    }

    private fun addSessionHost(sessionHostData: AspireSessionHostServiceData) {
        LOG.trace("Adding a new Aspire host $sessionHostData")
        val aspireHost = AspireSessionHostServiceContributor(sessionHostData)
        sessionHosts[sessionHostData.id] = aspireHost
        sessions[sessionHostData.id] = mutableMapOf()
        resources[sessionHostData.id] = mutableMapOf()
        val event = ServiceEventListener.ServiceEvent.createEvent(
            ServiceEventListener.EventType.SERVICE_ADDED,
            aspireHost,
            AspireServiceContributor::class.java
        )
        serviceEventPublisher.handle(event)
    }

    private fun removeSessionHost(sessionHostData: AspireSessionHostServiceData) {
        LOG.trace("Removing the Aspire host ${sessionHostData.id}")
        val aspireHost = sessionHosts.remove(sessionHostData.id)
        sessions.remove(sessionHostData.id)
        resources.remove(sessionHostData.id)
        if (aspireHost == null) return
        val event = ServiceEventListener.ServiceEvent.createEvent(
            ServiceEventListener.EventType.SERVICE_REMOVED,
            aspireHost,
            AspireServiceContributor::class.java
        )
        serviceEventPublisher.handle(event)
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
        sessionHostConfig: AspireSessionHostConfig,
        sessionHostModel: AspireSessionHostModel,
        sessionHostLifetime: Lifetime
    ) {
        val sessionEvents = Channel<AspireSessionEvent>(Channel.UNLIMITED)
        sessionHostLifetime.launchOnUi {
            sessionEvents.consumeAsFlow().collect {
                when (it) {
                    is AspireSessionStarted -> {
                        LOG.trace("Aspire session started (${it.id}, ${it.pid})")
                        sessionHostModel.processStarted.fire(ProcessStarted(it.id, it.pid))
                    }

                    is AspireSessionTerminated -> {
                        LOG.trace("Aspire session terminated (${it.id}, ${it.exitCode})")
                        sessionHostModel.processTerminated.fire(ProcessTerminated(it.id, it.exitCode))
                    }

                    is AspireSessionLogReceived -> {
                        LOG.trace("Aspire session log received (${it.id}, ${it.isStdErr}, ${it.message})")
                        sessionHostModel.logReceived.fire(LogReceived(it.id, it.isStdErr, it.message))
                    }
                }
            }
        }

        withUiContext {
            sessionHostModel.upsertSession.setSuspend { _, model ->
                upsertSession(model, sessionEvents, sessionHostConfig, sessionHostLifetime)
            }

            sessionHostModel.deleteSession.setSuspend { _, sessionId ->
                deleteSession(sessionId, sessionHostConfig.debugSessionToken)
            }

            sessionHostModel.resources.view(sessionHostLifetime) { resourceLifetime, resourceId, resource ->
                viewResource(resourceId, resource, resourceLifetime, sessionHostConfig)
            }
        }
    }

    private suspend fun upsertSession(
        sessionModel: SessionModel,
        sessionEvents: Channel<AspireSessionEvent>,
        sessionHostConfig: AspireSessionHostConfig,
        sessionHostLifetime: Lifetime
    ): SessionUpsertResult? {
        val (sessionId, lifetimes) = mutex.withLock {
            val sessionByHost = sessions[sessionHostConfig.debugSessionToken] ?: return null
            val previousValue = sessionByHost[sessionModel.projectPath]
            if (previousValue != null) {
                previousValue
            } else {
                val newSessionId = UUID.randomUUID().toString()
                val lifetimes = SequentialLifetimes(sessionHostLifetime)
                val pair = newSessionId to lifetimes
                sessionByHost[sessionModel.projectPath] = pair
                pair
            }
        }

        val lifetime = lifetimes.next()

        LOG.trace("Starting new session with runner (project $sessionModel)")
        val runner = AspireSessionRunner.getInstance(project)
        return runner.runSession(
            sessionId,
            sessionModel,
            lifetime,
            sessionEvents,
            sessionHostConfig.isDebug,
            sessionHostConfig.openTelemetryProtocolServerPort
        )
    }

    private suspend fun deleteSession(sessionId: String, sessionHostId: String): Boolean {
        val lifetimes = mutex.withLock {
            val sessionByHost = sessions[sessionHostId] ?: return false
            val entry = sessionByHost.entries.firstOrNull { it.value.first == sessionId } ?: return false
            sessionByHost.remove(entry.key)
            entry.value.second
        }

        lifetimes.terminateCurrent()

        return true
    }

    private fun viewResource(
        resourceId: String,
        resource: ResourceWrapper,
        resourceLifetime: Lifetime,
        sessionHostConfig: AspireSessionHostConfig
    ) {
        val sessionHost = sessionHosts[sessionHostConfig.debugSessionToken] ?: return
        val resourcesByHost = resources[sessionHostConfig.debugSessionToken] ?: return

        val resourceService = AspireResourceService(resource, resourceLifetime, sessionHost, project)
        resourcesByHost.addUnique(resourceLifetime, resourceId, resourceService)
        resource.isInitialized.set(true)

        resourceLifetime.bracketIfAlive({
            val serviceEvent = ServiceEventListener.ServiceEvent.createEvent(
                ServiceEventListener.EventType.SERVICE_STRUCTURE_CHANGED,
                sessionHost,
                AspireServiceContributor::class.java
            )
            project.messageBus.syncPublisher(ServiceEventListener.TOPIC).handle(serviceEvent)
        }, {
            val serviceEvent = ServiceEventListener.ServiceEvent.createEvent(
                ServiceEventListener.EventType.SERVICE_STRUCTURE_CHANGED,
                sessionHost,
                AspireServiceContributor::class.java
            )
            project.messageBus.syncPublisher(ServiceEventListener.TOPIC).handle(serviceEvent)
        })
    }
}