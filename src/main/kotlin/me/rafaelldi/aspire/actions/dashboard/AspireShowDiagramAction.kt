package me.rafaelldi.aspire.actions.dashboard

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import kotlinx.coroutines.launch
import me.rafaelldi.aspire.AspireService
import me.rafaelldi.aspire.diagram.DiagramService
import me.rafaelldi.aspire.services.AspireServiceManager
import me.rafaelldi.aspire.settings.AspireSettings
import me.rafaelldi.aspire.util.ASPIRE_HOST_PATH

class AspireShowDiagramAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val hostPath = event.getData(ASPIRE_HOST_PATH) ?: return
        val hostService = AspireServiceManager
            .getInstance(project)
            .getHost(hostPath)
            ?: return
        if (!hostService.isActive) return

        val diagramService = DiagramService.getInstance(project)
        AspireService.getInstance(project).scope.launch {
            diagramService.showDiagram(hostService)
        }
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        val hostPath = event.getData(ASPIRE_HOST_PATH)
        if (project == null || hostPath == null) {
            event.presentation.isEnabledAndVisible = false
            return
        }

        val hostService = AspireServiceManager
            .getInstance(project)
            .getHost(hostPath)
        if (hostService == null || !hostService.isActive) {
            event.presentation.isEnabledAndVisible = false
            return
        }

        val telemetryEnabled = AspireSettings.getInstance().collectTelemetry
        event.presentation.isEnabledAndVisible = telemetryEnabled
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}