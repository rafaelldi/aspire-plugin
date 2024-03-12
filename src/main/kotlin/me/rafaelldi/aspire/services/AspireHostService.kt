package me.rafaelldi.aspire.services

import com.intellij.execution.ExecutionResult
import com.intellij.execution.services.ServiceViewProvidingContributor
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.project.Project
import com.jetbrains.rd.util.lifetime.Lifetime
import me.rafaelldi.aspire.generated.AspireSessionHostModel
import java.nio.file.Path
import kotlin.io.path.absolutePathString

class AspireHostService(
    name: String,
    val projectPath: Path,
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

    var executionConsole: ExecutionConsole? = null
        private set


    fun startHost(
        aspireHostDashboardUrl: String,
        sessionHostModel: AspireSessionHostModel,
        aspireHostLifetime: Lifetime
    ) {
        isActive = true
        dashboardUrl = aspireHostDashboardUrl
        model = sessionHostModel
        lifetime = aspireHostLifetime
    }

    fun stopHost() {
        isActive = false
        dashboardUrl = null
        model = null
        lifetime = null
    }

    fun update(name: String) {
        displayName = name
    }

    fun update(executionResult: ExecutionResult) {
        executionConsole = executionResult.executionConsole
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