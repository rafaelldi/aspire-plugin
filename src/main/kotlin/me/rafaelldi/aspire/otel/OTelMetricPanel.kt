package me.rafaelldi.aspire.otel

import com.intellij.openapi.Disposable
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SideBorder
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.SimpleNode
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.ui.treeStructure.SimpleTreeStructure
import com.intellij.util.ui.components.BorderLayoutPanel
import me.rafaelldi.aspire.AspireBundle
import me.rafaelldi.aspire.services.AspireResourceService
import javax.swing.tree.TreeSelectionModel

class OTelMetricPanel(private val resourceService: AspireResourceService) : BorderLayoutPanel(), Disposable {
    private val splitter = OnePixelSplitter(false)

    private val structure = MetricTreeStructure(resourceService)
    private val treeModel = StructureTreeModel(structure, this)
    private val tree: SimpleTree

    init {
        tree = SimpleTree().apply {
            model = treeModel
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            isRootVisible = false
        }

        splitter.apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree, SideBorder.NONE)
            secondComponent = JBPanelWithEmptyText()
                .withEmptyText(AspireBundle.message("service.tab.metrics.select.metric"))
        }

        add(splitter)
    }

    fun update() {
        treeModel.invalidateAsync()
    }

    override fun dispose() {
    }
}

class MetricTreeStructure(resourceService: AspireResourceService) : SimpleTreeStructure() {
    private val root = MetricRootNode(resourceService)

    override fun getRootElement() = root
}

class MetricRootNode(private val resourceService: AspireResourceService) : SimpleNode() {
    override fun getChildren(): Array<out SimpleNode?> {
        return resourceService.getMetricsIds()
            .sortedBy { it }
            .map { MetricScopeNode(it) }
            .toTypedArray()
    }

    override fun isAutoExpandNode() = true
}

class MetricScopeNode(private val scope: String) : SimpleNode() {
    init {
        presentation.presentableText = scope
    }
    override fun getChildren(): Array<out SimpleNode?> {
        return NO_CHILDREN
    }
    override fun isAutoExpandNode() = true
}