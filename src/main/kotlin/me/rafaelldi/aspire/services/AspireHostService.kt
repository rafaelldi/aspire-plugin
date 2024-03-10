package me.rafaelldi.aspire.services

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.services.ServiceEventListener
import com.intellij.execution.services.ServiceViewProvidingContributor
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.jetbrains.rd.util.lifetime.Lifetime
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import me.rafaelldi.aspire.AspireService
import me.rafaelldi.aspire.generated.AspireSessionHostModel
import java.nio.file.Path
import kotlin.io.path.absolutePathString

class AspireHostService(
    name: String,
    val projectPath: Path,
    private val project: Project
) : ServiceViewProvidingContributor<AspireResourceService, AspireHostService> {

    private val viewDescriptor by lazy { AspireHostServiceViewDescriptor(this) }

    val projectPathString = projectPath.absolutePathString()

    var displayName: String = name
        private set
    var isActive: Boolean = false
        private set
    var dashboardUrl: String? = null
        private set
    var model: AspireSessionHostModel? = null
        private set
    var lifetime: Lifetime? = null
        private set

    private val consoleView: ConsoleView = TextConsoleBuilderFactory
        .getInstance()
        .createBuilder(project)
        .apply { setViewer(true) }
        .console

    fun getConsole() = consoleView

    init {
        Disposer.register(AspireService.getInstance(project), consoleView)
    }

    fun startHost(
        aspireHostDashboardUrl: String,
        aspireHostLogFlow: SharedFlow<AspireHostLog>,
        sessionHostModel: AspireSessionHostModel,
        aspireHostLifetime: Lifetime
    ) {
        isActive = true
        dashboardUrl = aspireHostDashboardUrl
        model = sessionHostModel
        lifetime = aspireHostLifetime
        consoleView.clear()

        aspireHostLifetime.coroutineScope.launch {
            aspireHostLogFlow.collect {
                consoleView.print(
                    it.text,
                    if (!it.isError) ConsoleViewContentType.NORMAL_OUTPUT
                    else ConsoleViewContentType.ERROR_OUTPUT
                )
            }
        }

        val serviceEvent = ServiceEventListener.ServiceEvent.createEvent(
            ServiceEventListener.EventType.SERVICE_CHANGED,
            this,
            AspireServiceContributor::class.java
        )
        project.messageBus.syncPublisher(ServiceEventListener.TOPIC).handle(serviceEvent)
    }

    fun stopHost() {
        isActive = false
        dashboardUrl = null
        model = null
        lifetime = null

        val serviceEvent = ServiceEventListener.ServiceEvent.createEvent(
            ServiceEventListener.EventType.SERVICE_CHANGED,
            this,
            AspireServiceContributor::class.java
        )
        project.messageBus.syncPublisher(ServiceEventListener.TOPIC).handle(serviceEvent)
    }

    fun update(name: String) {
        displayName = name

        val serviceEvent = ServiceEventListener.ServiceEvent.createEvent(
            ServiceEventListener.EventType.SERVICE_CHANGED,
            this,
            AspireServiceContributor::class.java
        )
        project.messageBus.syncPublisher(ServiceEventListener.TOPIC).handle(serviceEvent)
    }

    override fun getViewDescriptor(project: Project) = viewDescriptor

    override fun getServices(project: Project) =
        AspireServiceManager.getInstance(project)
            .getResourceServices(projectPathString)
            .toMutableList()

    override fun asService() = this

    override fun getServiceDescriptor(
        project: Project,
        service: AspireResourceService
    ) = AspireResourceServiceViewDescriptor(service)
}