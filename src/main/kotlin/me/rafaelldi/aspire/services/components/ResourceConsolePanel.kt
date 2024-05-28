package me.rafaelldi.aspire.services.components

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import me.rafaelldi.aspire.services.AspireResource

class ResourceConsolePanel(resourceService: AspireResource) : BorderLayoutPanel() {
    init {
        border = JBUI.Borders.empty()
        add(resourceService.consoleView.component)
    }
}