package me.rafaelldi.aspire.services.components

import com.intellij.ide.CopyProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.ui.JBUI
import me.rafaelldi.aspire.otel.MetricId
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class MetricTree(private val parentComponent: ResourceMetricComponent) : SimpleTree(), CopyProvider {
    private val rootNode = MetricRootNode()

    init {
        model = DefaultTreeModel(rootNode)

        isRootVisible = false
        showsRootHandles = false
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        border = JBUI.Borders.emptyTop(5)

        TreeSpeedSearch.installOn(this, true) {
            return@installOn when (val node = it.lastPathComponent) {
                is MetricTreeNode -> node.name
                else -> null
            }
        }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                nodeSelected(event)
                return true
            }
        }.installOn(this)

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                val keyCode = event.keyCode
                if (keyCode == KeyEvent.VK_ENTER || keyCode == KeyEvent.VK_SPACE) {
                    nodeSelected(event)
                }
            }
        })

        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean
            ) {
                if (value is MetricTreeNode) {
                    value.render(this)
                }
            }
        }

        updateUI()
    }

    private val selectedNode: MetricTreeNode?
        get() = lastSelectedPathComponent as? MetricTreeNode

    private fun nodeSelected(event: InputEvent) {
        val selected = selectedNode as? MetricNameNode ?: return
        parentComponent.metricSelected(selected.metricId)
    }

    fun addMetricId(metricId: MetricId) {
        val scopeNode = insertScopeNode(metricId)
        insertMetricNode(scopeNode, metricId)
        expandPath(TreePath(scopeNode.path))
    }

    private fun insertScopeNode(metricId: MetricId): MetricScopeNode {
        val scopeNode = rootNode.children().asSequence().firstOrNull {
            val current = it as? MetricScopeNode ?: return@firstOrNull false
            return@firstOrNull current.name == metricId.scopeName
        } as? MetricScopeNode

        if (scopeNode != null) return scopeNode

        val newScopeNode = MetricScopeNode(metricId.scopeName)
        (model as? DefaultTreeModel)?.insertNodeInto(newScopeNode, rootNode, 0)

        return newScopeNode
    }

    private fun insertMetricNode(scopeNode: MetricScopeNode, metricId: MetricId) {
        val node = MetricNameNode(metricId)

        val index = scopeNode.children().asSequence().indexOfFirst {
            val current = it as? MetricNameNode ?: return@indexOfFirst false
            node.name < current.name
        }

        (model as? DefaultTreeModel)
            ?.insertNodeInto(node, scopeNode, if (index == -1) scopeNode.childCount else index)
    }

    override fun performCopy(dataContext: DataContext) {
        if (!isSelectionEmpty) {
            val selected = selectedNode ?: return
            CopyPasteManager.getInstance().setContents(StringSelection(selected.name))
        }
    }

    override fun isCopyEnabled(dataContext: DataContext) = !isSelectionEmpty

    override fun isCopyVisible(dataContext: DataContext) = !isSelectionEmpty

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}