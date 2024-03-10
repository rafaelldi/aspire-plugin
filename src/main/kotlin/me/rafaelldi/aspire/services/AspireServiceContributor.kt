package me.rafaelldi.aspire.services

import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.SimpleServiceViewDescriptor
import com.intellij.openapi.project.Project
import me.rafaelldi.aspire.AspireIcons

class AspireServiceContributor : ServiceViewContributor<AspireHostService> {
    override fun getViewDescriptor(project: Project) =
        SimpleServiceViewDescriptor("Aspire", AspireIcons.Service)

    override fun getServices(project: Project) =
        AspireServiceManager.getInstance(project)
            .getHostServices()
            .toMutableList()

    override fun getServiceDescriptor(project: Project, host: AspireHostService) =
        host.getViewDescriptor(project)
}