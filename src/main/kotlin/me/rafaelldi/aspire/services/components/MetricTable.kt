package me.rafaelldi.aspire.services.components

import com.intellij.ui.DoubleClickListener
import com.intellij.ui.table.JBTable
import me.rafaelldi.aspire.AspireBundle
import me.rafaelldi.aspire.services.AspireResourceMetricKey
import java.awt.event.MouseEvent
import javax.swing.table.DefaultTableModel

class MetricTable(private val parentPanel: ResourceMetricComponent) : JBTable() {
    companion object {
        private val SCOPE_COLUMN = AspireBundle.getMessage("service.tab.metrics.table.scope")
        private val NAME_COLUMN = AspireBundle.getMessage("service.tab.metrics.table.name")
        private val VALUE_COLUMN = AspireBundle.getMessage("service.tab.metrics.table.value")
        private val UNIT_COLUMN = AspireBundle.getMessage("service.tab.metrics.table.unit")
    }

    private val metricRowMap: MutableMap<AspireResourceMetricKey, Int> = mutableMapOf()
    private val tableModel = DefaultTableModel(arrayOf(SCOPE_COLUMN, NAME_COLUMN, VALUE_COLUMN, UNIT_COLUMN), 0)

    init {
        model = tableModel
        setShowGrid(false)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                rowSelected()
                return true
            }
        }.installOn(this)
    }

    override fun isCellEditable(row: Int, column: Int): Boolean = false

    private fun rowSelected() {
        val row = selectedRow
        val scope = tableModel.getValueAt(row, 0) as String
        val metric = tableModel.getValueAt(row, 1) as String
        val value = (tableModel.getValueAt(row, 2) as String).toDouble()
        val unit = tableModel.getValueAt(row, 3) as String

        parentPanel.metricSelected(scope, metric, value, unit)
    }

//    fun addOrUpdate(metrics: Map<AspireResourceMetricKey, ResourceMetric>) {
//        for (metric in metrics) {
//            val stringValue = String.format(Locale.ROOT, "%.2f", metric.value.value)
//            val row = metricRowMap[metric.key]
//            if (row != null) {
//                tableModel.setValueAt(stringValue, row, 2)
//            } else {
//                val newRow = arrayOf(metric.value.scope, metric.value.name, stringValue, metric.value.unit ?: "")
//                tableModel.addRow(newRow)
//                metricRowMap[metric.key] = (metricRowMap.values.maxOrNull() ?: -1) + 1
//            }
//        }
//    }
}