package me.rafaelldi.aspire.services

import com.intellij.execution.ExecutionResult
import com.intellij.execution.services.ServiceViewProvidingContributor
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rider.run.ConsoleKind
import com.jetbrains.rider.run.createConsole
import me.rafaelldi.aspire.AspireService
import me.rafaelldi.aspire.generated.AspireSessionHostModel
import java.nio.file.Path
import kotlin.io.path.absolutePathString

class AspireHost(
    name: String,
    val projectPath: Path,
) : ServiceViewProvidingContributor<AspireResource, AspireHost> {

    private val viewDescriptor by lazy { AspireHostViewDescriptor(this) }

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

    var consoleView: ConsoleView? = null
        private set

    fun start(
        aspireHostDashboardUrl: String,
        sessionHostModel: AspireSessionHostModel,
        aspireHostServiceLifetime: Lifetime
    ) {
        isActive = true
        dashboardUrl = aspireHostDashboardUrl
        model = sessionHostModel
        lifetime = aspireHostServiceLifetime
    }

    fun stop() {
        isActive = false
        dashboardUrl = null
        model = null
        lifetime = null
    }

    fun update(name: String) {
        displayName = name
    }

    fun update(executionResult: ExecutionResult, project: Project) {
        val console = createConsole(
            ConsoleKind.Normal,
            executionResult.processHandler,
            project
        )
        Disposer.register(AspireService.getInstance(project), console)
        consoleView = console
    }

    override fun getViewDescriptor(project: Project) = viewDescriptor

    override fun getServices(project: Project) =
        AspireServiceManager.getInstance(project)
            .getResources(projectPathString)
            .toMutableList()

    override fun asService() = this

    override fun getServiceDescriptor(
        project: Project,
        service: AspireResource
    ) = AspireResourceViewDescriptor(service, project)
}