package me.rafaelldi.aspire.sessionHost

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.jetbrains.rd.framework.util.setSuspend
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.lifetime.SequentialLifetimes
import com.jetbrains.rd.util.threading.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rafaelldi.aspire.generated.*
import me.rafaelldi.aspire.run.AspireHostProjectConfig
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class AspireSessionHostManager(private val project: Project) {
    companion object {
        fun getInstance(project: Project) = project.service<AspireSessionHostManager>()

        private val LOG = logger<AspireSessionHostManager>()
    }

    private val mutex = Mutex()

    private val sessions = ConcurrentHashMap<String, MutableMap<String, Pair<String, SequentialLifetimes>>>()

    suspend fun runSessionHost(
        aspireHostConfig: AspireHostProjectConfig,
        protocolServerPort: Int,
        sessionHostModel: AspireSessionHostModel,
        aspireHostLifetime: LifetimeDefinition
    ) {
        LOG.info("Starting Aspire session host: $aspireHostConfig")

        subscribe(aspireHostConfig, sessionHostModel, aspireHostLifetime)

        aspireHostLifetime.bracketIfAlive({
            sessions[aspireHostConfig.debugSessionToken] = mutableMapOf()
        }, {
            sessions.remove(aspireHostConfig.debugSessionToken)
        })

        LOG.trace("Starting new session hosts with runner")
        val sessionHostRunner = AspireSessionHostRunner.getInstance(project)
        sessionHostRunner.runSessionHost(
            aspireHostConfig,
            protocolServerPort,
            aspireHostLifetime
        )
    }

    private suspend fun subscribe(
        aspireHostConfig: AspireHostProjectConfig,
        sessionHostModel: AspireSessionHostModel,
        aspireHostLifetime: Lifetime
    ) {
        LOG.trace("Subscribing to protocol model")
        val sessionEvents = Channel<AspireSessionEvent>(Channel.UNLIMITED)
        aspireHostLifetime.launch(Dispatchers.EDT) {
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

        withContext(Dispatchers.EDT) {
            sessionHostModel.upsertSession.setSuspend { _, model ->
                upsertSession(model, sessionEvents, aspireHostConfig, aspireHostLifetime)
            }

            sessionHostModel.deleteSession.setSuspend { _, sessionId ->
                deleteSession(sessionId, aspireHostConfig.debugSessionToken)
            }
        }
    }

    private suspend fun upsertSession(
        sessionModel: SessionModel,
        sessionEvents: Channel<AspireSessionEvent>,
        aspireHostConfig: AspireHostProjectConfig,
        aspireHostLifetime: Lifetime
    ): SessionUpsertResult? {
        LOG.trace("Upserting a session ${sessionModel.projectPath}")

        val (sessionId, lifetimes) = mutex.withLock {
            val sessionByHost = sessions[aspireHostConfig.debugSessionToken] ?: return null
            val previousValue = sessionByHost[sessionModel.projectPath]
            if (previousValue != null) {
                previousValue
            } else {
                val newSessionId = UUID.randomUUID().toString()
                val lifetimes = SequentialLifetimes(aspireHostLifetime)
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
            aspireHostConfig.isDebug,
            aspireHostConfig.openTelemetryProtocolServerPort
        )
    }

    private suspend fun deleteSession(sessionId: String, sessionHostId: String): Boolean {
        LOG.trace("Deleting session $sessionId")

        val lifetimes = mutex.withLock {
            val sessionByHost = sessions[sessionHostId] ?: return false
            val entry = sessionByHost.entries.firstOrNull { it.value.first == sessionId } ?: return false
            sessionByHost.remove(entry.key)
            entry.value.second
        }

        lifetimes.terminateCurrent()

        return true
    }
}