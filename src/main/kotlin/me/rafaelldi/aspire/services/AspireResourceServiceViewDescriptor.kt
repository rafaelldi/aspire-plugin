@file:Suppress("UnstableApiUsage")

package me.rafaelldi.aspire.services

import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.rd.util.withUiContext
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBTabbedPane
import com.jetbrains.rd.util.threading.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import me.rafaelldi.aspire.AspireBundle
import me.rafaelldi.aspire.otel.OTelMetricPanel
import me.rafaelldi.aspire.services.components.ResourceConsolePanel
import me.rafaelldi.aspire.services.components.ResourceDashboardPanel
import me.rafaelldi.aspire.services.components.ResourceMetricPanel
import me.rafaelldi.aspire.settings.AspireSettings
import me.rafaelldi.aspire.util.ASPIRE_RESOURCE_STATE
import me.rafaelldi.aspire.util.ASPIRE_RESOURCE_TYPE
import me.rafaelldi.aspire.util.ASPIRE_RESOURCE_UID
import me.rafaelldi.aspire.util.getIcon
import java.awt.BorderLayout
import javax.swing.JPanel
import kotlin.time.Duration.Companion.seconds

class AspireResourceServiceViewDescriptor(
    private val resourceService: AspireResourceService
) : ServiceViewDescriptor, DataProvider {

    private val toolbarActions = DefaultActionGroup(
        ActionManager.getInstance().getAction("Aspire.Resource.Restart"),
        ActionManager.getInstance().getAction("Aspire.Resource.Restart.Debug"),
        ActionManager.getInstance().getAction("Aspire.Resource.Stop")
    )

    private val metricPanelDelegate = lazy { ResourceMetricPanel(resourceService) }
    private val metricPanel by metricPanelDelegate

    private val metricPanelDelegate2 = lazy { OTelMetricPanel(resourceService) }
    private val metricPanel2 by metricPanelDelegate2

    private val mainPanel by lazy {
        val tabs = JBTabbedPane()
        tabs.addTab(AspireBundle.getMessage("service.tab.dashboard"), ResourceDashboardPanel(resourceService))
        tabs.addTab(AspireBundle.getMessage("service.tab.console"), ResourceConsolePanel(resourceService))
        if (AspireSettings.getInstance().collectTelemetry) {
            tabs.addTab(AspireBundle.getMessage("service.tab.metrics"), metricPanel)
            tabs.addTab(AspireBundle.getMessage("service.tab.metrics"), metricPanel2)
        }

        JPanel(BorderLayout()).apply {
            add(tabs, BorderLayout.CENTER)
        }
    }

    init {
        if (AspireSettings.getInstance().collectTelemetry) {
            resourceService.lifetime.launch(Dispatchers.Default) {
                while (true) {
                    delay(1.seconds)
                    withUiContext {
                        update()
                    }
                }
            }
        }
    }

    private suspend fun update() {
        if (metricPanelDelegate.isInitialized()) {
            metricPanel.update()
        }
        if (metricPanelDelegate2.isInitialized()) {
            resourceService.updateMetricIds()
            metricPanel2.update()
        }
    }

    override fun getPresentation() = PresentationData().apply {
        val icon = getIcon(resourceService.type, resourceService.state)
        setIcon(icon)
        addText(resourceService.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    override fun getContentComponent() = mainPanel

    override fun getToolbarActions() = toolbarActions

    override fun getDataProvider() = this

    override fun getData(dataId: String) =
        if (ASPIRE_RESOURCE_UID.`is`(dataId)) resourceService.uid
        else if (ASPIRE_RESOURCE_TYPE.`is`(dataId)) resourceService.type
        else if (ASPIRE_RESOURCE_STATE.`is`(dataId)) resourceService.state
        else null
}