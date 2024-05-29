package me.rafaelldi.aspire.services.components

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SideBorder
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.ui.components.BorderLayoutPanel
import com.jetbrains.rd.util.lifetime.SequentialLifetimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rafaelldi.aspire.AspireBundle
import me.rafaelldi.aspire.generated.ResourceMetricId
import me.rafaelldi.aspire.otel.MetricId
import me.rafaelldi.aspire.otel.OTelService
import me.rafaelldi.aspire.services.AspireResource

class ResourceMetricComponent(
    private val resourceService: AspireResource,
    private val project: Project
) {
    private val tree = MetricTree(this)
    private var chartPanel: ResourceMetricChartPanel? = null

    private val splitter = OnePixelSplitter(false).apply {
        firstComponent = ScrollPaneFactory.createScrollPane(tree, SideBorder.NONE)
        secondComponent = JBPanelWithEmptyText()
            .withEmptyText(AspireBundle.message("service.tab.metrics.select.metric"))
    }

    val panel = BorderLayoutPanel().apply {
        add(splitter)
    }

    private val metricIds = mutableMapOf<MetricId, Unit>()
    private val metricIdFlow = MutableSharedFlow<MetricId>(
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        extraBufferCapacity = 100
    )
    
    private val subscriptionLifetimes = SequentialLifetimes(resourceService.lifetime)

    init {
        resourceService.lifetime.coroutineScope.launch {
            metricIdFlow.collect {
                handleMetricId(it)
            }
        }

        getMetricsFromModelAndSubscribe()
    }

    private fun getMetricsFromModelAndSubscribe() {
        val resourceId = resourceService.uid
        val hostProjectPath = resourceService.hostProjectPath
        val service = OTelService.getInstance(project)
        service.subscribeToMetricIds(
            hostProjectPath,
            resourceId,
            resourceService.lifetime
        ) {
            metricIdFlow.tryEmit(it)
        }
    }

    fun metricSelected(scope: String, metric: String, value: Double, unit: String) {
    }

    private fun handleMetricId(metricId: MetricId) {
        val currentValue = metricIds.putIfAbsent(metricId, Unit)
        if (currentValue != null) return
        tree.addMetricId(metricId)
    }

    fun metricSelected(metricId: MetricId) {
        val resourceId = resourceService.uid
        val hostProjectPath = resourceService.hostProjectPath
        val service = OTelService.getInstance(project)
        val lifetime = subscriptionLifetimes.next()
        val resourceMetricId = ResourceMetricId(resourceId, metricId.scopeName, metricId.metricName)

        resourceService.lifetime.coroutineScope.launch {
            val metricDetails = withContext(Dispatchers.EDT) {
                service.getMetricDetails(hostProjectPath, resourceMetricId)
            } ?: return@launch

            chartPanel = ResourceMetricChartPanel(metricDetails, 0.0)
            splitter.secondComponent = chartPanel

            withContext(Dispatchers.EDT) {
                service.subscribeToMetricValues(
                    hostProjectPath,
                    resourceMetricId,
                    lifetime
                ) {
                    val i = 0
                }
            }
        }
    }
}