package me.rafaelldi.aspire.services.components

import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SideBorder
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.ui.components.BorderLayoutPanel
import me.rafaelldi.aspire.AspireBundle
import me.rafaelldi.aspire.services.AspireResourceMetricKey
import me.rafaelldi.aspire.services.AspireResourceService

class ResourceMetricPanel(private val resourceService: AspireResourceService) : BorderLayoutPanel() {
    private val tree = MetricTree(this)
    private var chosenMetric: AspireResourceMetricKey? = null
    private var chartPanel: ResourceMetricChartPanel? = null

    private val splitter = OnePixelSplitter(false).apply {
        firstComponent = ScrollPaneFactory.createScrollPane(tree, SideBorder.NONE)
        secondComponent = JBPanelWithEmptyText()
            .withEmptyText(AspireBundle.message("service.tab.metrics.select.metric"))
    }

    init {
        add(splitter)
    }

    fun metricSelected(scope: String, metric: String, value: Double, unit: String) {
        chosenMetric = AspireResourceMetricKey(scope, metric)
        chartPanel = ResourceMetricChartPanel(metric, value, unit)
        splitter.secondComponent = chartPanel
    }

    fun update() {
//        val metrics = resourceService.getMetrics()
//        table.addOrUpdate(metrics)
//        chartPanel?.let {
//            val key = chosenMetric ?: return@let
//            val metric = metrics[key] ?: return@let
//            it.update(metric.value, metric.timestamp)
//        }
    }
}