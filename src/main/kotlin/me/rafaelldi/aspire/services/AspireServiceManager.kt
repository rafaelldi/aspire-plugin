package me.rafaelldi.aspire.services

import com.intellij.execution.ExecutionResult
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.services.ServiceEventListener
import com.intellij.execution.services.ServiceViewManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.application
import com.jetbrains.rd.util.addUnique
import com.jetbrains.rd.util.lifetime.Lifetime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rafaelldi.aspire.generated.AspireSessionHostModel
import me.rafaelldi.aspire.generated.ResourceState
import me.rafaelldi.aspire.generated.ResourceType
import me.rafaelldi.aspire.generated.ResourceWrapper
import me.rafaelldi.aspire.run.AspireHostConfiguration
import me.rafaelldi.aspire.run.AspireHostConfig
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.nameWithoutExtension

@Service(Service.Level.PROJECT)
class AspireServiceManager(private val project: Project) {
    companion object {
        fun getInstance(project: Project) = project.service<AspireServiceManager>()

        private val LOG = logger<AspireServiceManager>()
    }

    private val hosts = ConcurrentHashMap<String, AspireHost>()
    private val resources = ConcurrentHashMap<String, MutableMap<String, AspireResource>>()

    fun getHosts() = hosts.values.toList()
    fun getHost(hostPath: String) = hosts[hostPath]
    fun getResources(hostPath: String) =
        resources[hostPath]?.values
            ?.asSequence()
            ?.filter { it.type != ResourceType.Unknown }
            ?.filter { it.state != ResourceState.Hidden }
            ?.sortedBy { it.type }
            ?.toList()
            ?: emptyList()

    private val serviceEventPublisher = project.messageBus.syncPublisher(ServiceEventListener.TOPIC)

    fun addAspireHost(host: AspireHost) {
        if (hosts.containsKey(host.projectPathString)) return

        LOG.trace("Adding a new Aspire host ${host.projectPathString}")
        hosts[host.projectPathString] = host
        resources[host.projectPathString] = mutableMapOf()

        val event = ServiceEventListener.ServiceEvent.createEvent(
            ServiceEventListener.EventType.SERVICE_ADDED,
            host,
            AspireServiceViewContributor::class.java
        )
        serviceEventPublisher.handle(event)
    }

    fun removeAspireHost(hostPath: Path) {
        val hostPathString = hostPath.absolutePathString()
        LOG.trace("Removing the Aspire host $hostPathString")

        val host = hosts.remove(hostPathString)
        resources.remove(hostPathString)
        if (host == null) return

        val event = ServiceEventListener.ServiceEvent.createEvent(
            ServiceEventListener.EventType.SERVICE_REMOVED,
            host,
            AspireServiceViewContributor::class.java
        )
        serviceEventPublisher.handle(event)
    }

    fun updateAspireHost(hostPath: Path, name: String) {
        val hostPathString = hostPath.absolutePathString()
        LOG.trace("Updating the Aspire host $hostPathString")

        val host = hosts[hostPathString] ?: return
        host.update(name)

        sendServiceChangedEvent(host)
    }

    fun updateAspireHost(hostPath: Path, executionResult: ExecutionResult) {
        val hostPathString = hostPath.absolutePathString()
        LOG.trace("Setting the execution result to the Aspire host $hostPathString")

        val host = hosts[hostPathString] ?: return
        host.update(executionResult, project)

        sendServiceChangedEvent(host)
    }

    suspend fun startAspireHost(
        aspireHostConfig: AspireHostConfig,
        sessionHostModel: AspireSessionHostModel
    ) {
        val hostPathString = aspireHostConfig.aspireHostProjectPath.absolutePathString()
        LOG.trace("Starting the Aspire Host $hostPathString")

        val aspireHostServiceLifetime = aspireHostConfig.aspireHostLifetime.createNested()

        val hostService = hosts[hostPathString] ?: return

        val serviceViewManager = ServiceViewManager.getInstance(project)
        withContext(Dispatchers.EDT) {
            serviceViewManager.select(hostService, AspireServiceViewContributor::class.java, true, true)
        }

        aspireHostServiceLifetime.bracketIfAlive({
            hostService.start(
                aspireHostConfig.aspireHostProjectUrl,
                sessionHostModel,
                aspireHostServiceLifetime
            )
            sendServiceChangedEvent(hostService)
        }, {
            hostService.stop()
            sendServiceChangedEvent(hostService)
        })

        withContext(Dispatchers.EDT) {
            sessionHostModel.resources.view(aspireHostServiceLifetime) { resourceLifetime, resourceId, resource ->
                viewResource(resourceId, resource, resourceLifetime, hostService)
            }
        }
    }

    private fun sendServiceChangedEvent(host: AspireHost) {
        val event = ServiceEventListener.ServiceEvent.createEvent(
            ServiceEventListener.EventType.SERVICE_CHANGED,
            host,
            AspireServiceViewContributor::class.java
        )
        serviceEventPublisher.handle(event)
    }

    private fun viewResource(
        resourceId: String,
        resource: ResourceWrapper,
        resourceLifetime: Lifetime,
        hostService: AspireHost
    ) {
        LOG.trace("Adding a new resource $resourceId")

        val resourcesByHost = resources[hostService.projectPathString] ?: return

        val resourceService = AspireResource(resource, resourceLifetime, hostService, project)
        resourcesByHost.addUnique(resourceLifetime, resourceId, resourceService)

        val serviceViewManager = ServiceViewManager.getInstance(project)
        application.invokeLater {
            serviceViewManager.expand(hostService, AspireServiceViewContributor::class.java)
        }

        resource.isInitialized.set(true)

        resourceLifetime.bracketIfAlive({
            sendServiceStructureChangedEvent(hostService)
        }, {
            sendServiceStructureChangedEvent(hostService)
        })
    }

    private fun sendServiceStructureChangedEvent(host: AspireHost) {
        val serviceEvent = ServiceEventListener.ServiceEvent.createEvent(
            ServiceEventListener.EventType.SERVICE_STRUCTURE_CHANGED,
            host,
            AspireServiceViewContributor::class.java
        )
        project.messageBus.syncPublisher(ServiceEventListener.TOPIC).handle(serviceEvent)
    }

    class Listener(private val project: Project) : RunManagerListener {
        override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) {
            val configuration = settings.configuration
            if (configuration !is AspireHostConfiguration) return
            val params = configuration.parameters
            val projectPath = Path(params.projectFilePath)
            val name = projectPath.nameWithoutExtension
            val host = AspireHost(name, projectPath)
            getInstance(project).addAspireHost(host)
        }

        override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) {
            val configuration = settings.configuration
            if (configuration !is AspireHostConfiguration) return
            val params = configuration.parameters
            val projectPath = Path(params.projectFilePath)
            val name = projectPath.nameWithoutExtension
            getInstance(project).updateAspireHost(projectPath, name)
        }

        override fun runConfigurationRemoved(settings: RunnerAndConfigurationSettings) {
            val configuration = settings.configuration
            if (configuration !is AspireHostConfiguration) return
            val params = configuration.parameters
            val projectPath = Path(params.projectFilePath)
            getInstance(project).removeAspireHost(projectPath)
        }
    }
}