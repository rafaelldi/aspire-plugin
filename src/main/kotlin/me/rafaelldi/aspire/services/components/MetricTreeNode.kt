package me.rafaelldi.aspire.services.components

import com.intellij.ui.ColoredTreeCellRenderer
import javax.swing.tree.DefaultMutableTreeNode

sealed class MetricTreeNode(val name: String) : DefaultMutableTreeNode() {
    fun render(renderer: ColoredTreeCellRenderer) {
        renderer.append(name)
    }
}

class MetricRootNode : MetricTreeNode("Root")

class MetricScopeNode(metricScope: String) : MetricTreeNode(metricScope)

class MetricNameNode(metricName: String) : MetricTreeNode(metricName)