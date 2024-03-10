package me.rafaelldi.aspire.services

import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.services.ServiceEventListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.jetbrains.rd.util.addUnique
import com.jetbrains.rd.util.lifetime.Lifetime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import me.rafaelldi.aspire.generated.AspireSessionHostModel
import me.rafaelldi.aspire.generated.ResourceWrapper
import me.rafaelldi.aspire.run.AspireHostConfiguration
import me.rafaelldi.aspire.run.AspireHostProjectConfig
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

@Service(Service.Level.PROJECT)
class AspireServiceManager(private val project: Project) {
    companion object {
        fun getInstance(project: Project) = project.service<AspireServiceManager>()

        private val LOG = logger<AspireServiceManager>()
    }

    private val hostServices = ConcurrentHashMap<String, AspireHostService>()
    private val resourceServices = ConcurrentHashMap<String, MutableMap<String, AspireResourceService>>()

    fun getHostServices() = hostServices.values.toList()
    fun getHostService(hostPath: String) = hostServices[hostPath]
    fun getResourceServices(hostPath: String) =
        resourceServices[hostPath]?.values?.sortedBy { it.resourceType }?.toList() ?: emptyList()

    private val serviceEventPublisher = project.messageBus.syncPublisher(ServiceEventListener.TOPIC)

    fun addAspireHostService(host: AspireHostService) {
        LOG.trace("Adding a new Aspire host ${host.projectPathString}")
        hostServices[host.projectPathString] = host
        resourceServices[host.projectPathString] = mutableMapOf()

        val event = ServiceEventListener.ServiceEvent.createEvent(
            ServiceEventListener.EventType.SERVICE_ADDED,
            host,
            AspireServiceContributor::class.java
        )
        serviceEventPublisher.handle(event)
    }

    fun removeAspireHostService(hostPath: Path) {
        val hostPathString = hostPath.absolutePathString()
        LOG.trace("Removing the Aspire host $hostPathString")

        val host = hostServices.remove(hostPathString)
        resourceServices.remove(hostPathString)
        if (host == null) return

        val event = ServiceEventListener.ServiceEvent.createEvent(
            ServiceEventListener.EventType.SERVICE_REMOVED,
            host,
            AspireServiceContributor::class.java
        )
        serviceEventPublisher.handle(event)
    }

    fun updateAspireHostService(hostPath: Path, name: String) {
        val hostPathString = hostPath.absolutePathString()
        LOG.trace("Updating the Aspire host $hostPathString")

        val host = hostServices[hostPathString] ?: return
        host.update(name)

        val event = ServiceEventListener.ServiceEvent.createEvent(
            ServiceEventListener.EventType.SERVICE_CHANGED,
            host,
            AspireServiceContributor::class.java
        )
        serviceEventPublisher.handle(event)
    }

    suspend fun startAspireHostService(
        aspireHostConfig: AspireHostProjectConfig,
        aspireHostLogFlow: SharedFlow<AspireHostLog>,
        sessionHostModel: AspireSessionHostModel,
        aspireHostLifetime: Lifetime
    ) {
        val hostPathString = aspireHostConfig.aspireHostProjectPath.absolutePathString()
        LOG.trace("Starting the Aspire Host $hostPathString")

        val hostService = hostServices[hostPathString] ?: return
        aspireHostLifetime.bracketIfAlive({
            hostService.startHost(
                aspireHostConfig.aspireHostProjectUrl,
                aspireHostLogFlow,
                sessionHostModel,
                aspireHostLifetime
            )
        }, {
            hostService.stopHost()
        })

        withContext(Dispatchers.EDT) {
            sessionHostModel.resources.view(aspireHostLifetime) { resourceLifetime, resourceId, resource ->
                viewResource(resourceId, resource, resourceLifetime, hostService)
            }
        }
    }

    private fun viewResource(
        resourceId: String,
        resource: ResourceWrapper,
        resourceLifetime: Lifetime,
        hostService: AspireHostService
    ) {
        LOG.trace("Adding a new resource $resourceId")

        val resourcesByHost = resourceServices[hostService.projectPathString] ?: return

        val resourceService = AspireResourceService(resource, resourceLifetime, hostService, project)
        resourcesByHost.addUnique(resourceLifetime, resourceId, resourceService)
        resource.isInitialized.set(true)

        resourceLifetime.bracketIfAlive({
            val serviceEvent = ServiceEventListener.ServiceEvent.createEvent(
                ServiceEventListener.EventType.SERVICE_STRUCTURE_CHANGED,
                hostService,
                AspireServiceContributor::class.java
            )
            project.messageBus.syncPublisher(ServiceEventListener.TOPIC).handle(serviceEvent)
        }, {
            val serviceEvent = ServiceEventListener.ServiceEvent.createEvent(
                ServiceEventListener.EventType.SERVICE_STRUCTURE_CHANGED,
                hostService,
                AspireServiceContributor::class.java
            )
            project.messageBus.syncPublisher(ServiceEventListener.TOPIC).handle(serviceEvent)
        })
    }

    class Listener(private val project: Project) : RunManagerListener {
        override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) {
            val configuration = settings.configuration
            if (configuration !is AspireHostConfiguration) return
            val name = configuration.name
            val projectPath = Path(configuration.getProjectFilePath())
            val host = AspireHostService(name, projectPath, project)
            getInstance(project).addAspireHostService(host)
        }

        override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) {
            val configuration = settings.configuration
            if (configuration !is AspireHostConfiguration) return
            val name = configuration.name
            val projectPath = Path(configuration.getProjectFilePath())
            getInstance(project).updateAspireHostService(projectPath, name)
        }

        override fun runConfigurationRemoved(settings: RunnerAndConfigurationSettings) {
            val configuration = settings.configuration
            if (configuration !is AspireHostConfiguration) return
            val projectPath = Path(configuration.getProjectFilePath())
            getInstance(project).removeAspireHostService(projectPath)
        }
    }
}