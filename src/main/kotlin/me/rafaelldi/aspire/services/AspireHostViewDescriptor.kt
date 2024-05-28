@file:Suppress("UnstableApiUsage")

package me.rafaelldi.aspire.services

import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.ui.BadgeIconSupplier
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.ui.JBUI
import me.rafaelldi.aspire.AspireIcons
import me.rafaelldi.aspire.util.ASPIRE_HOST_PATH
import java.awt.BorderLayout
import javax.swing.JPanel

class AspireHostViewDescriptor(
    private val hostService: AspireHost
) : ServiceViewDescriptor, DataProvider {

    private val toolbarActions = DefaultActionGroup(
        ActionManager.getInstance().getAction("Aspire.Manifest"),
        ActionManager.getInstance().getAction("Aspire.Dashboard"),
        ActionManager.getInstance().getAction("Aspire.Diagram"),
        Separator(),
        ActionManager.getInstance().getAction("Aspire.Settings"),
        ActionManager.getInstance().getAction("Aspire.Help")
    )

    override fun getPresentation() = PresentationData().apply {
        var icon = AspireIcons.Service
        if (hostService.isActive) {
            icon = BadgeIconSupplier(icon).liveIndicatorIcon
        }
        setIcon(icon)
        addText(hostService.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    override fun getContentComponent(): JPanel {
        val console = hostService.consoleView
        val panel = if (console == null) {
            JBPanelWithEmptyText()
        } else {
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty()
                add(console.component)
            }
        }
        return panel
    }

    override fun getToolbarActions() = toolbarActions

    override fun getDataProvider() = this

    override fun getData(dataId: String) =
        if (ASPIRE_HOST_PATH.`is`(dataId)) hostService.projectPathString
        else null
}