package me.rafaelldi.aspire.services.components

import com.intellij.ui.ColoredTreeCellRenderer
import me.rafaelldi.aspire.otel.MetricId
import javax.swing.tree.DefaultMutableTreeNode

sealed class MetricTreeNode(val name: String) : DefaultMutableTreeNode() {
    fun render(renderer: ColoredTreeCellRenderer) {
        renderer.append(name)
    }
}

class MetricRootNode : MetricTreeNode("Root")

class MetricScopeNode(metricScope: String) : MetricTreeNode(metricScope)

class MetricNameNode(val metricId: MetricId) : MetricTreeNode(metricId.metricName)