package me.rafaelldi.aspire.services

import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.SimpleServiceViewDescriptor
import com.intellij.openapi.project.Project
import me.rafaelldi.aspire.AspireIcons

class AspireServiceViewContributor : ServiceViewContributor<AspireHost> {
    override fun getViewDescriptor(project: Project) =
        SimpleServiceViewDescriptor("Aspire", AspireIcons.Service)

    override fun getServices(project: Project) =
        AspireServiceManager.getInstance(project)
            .getHosts()
            .toMutableList()

    override fun getServiceDescriptor(project: Project, host: AspireHost) =
        host.getViewDescriptor(project)
}