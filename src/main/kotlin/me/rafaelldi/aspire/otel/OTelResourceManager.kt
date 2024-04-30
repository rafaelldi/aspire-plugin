package me.rafaelldi.aspire.otel

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rafaelldi.aspire.generated.AspireSessionHostModel
import me.rafaelldi.aspire.generated.RdOtelResourceMetrics
import me.rafaelldi.aspire.run.AspireHostProjectConfig

@Service(Service.Level.PROJECT)
class OTelResourceManager(private val project: Project, scope: CoroutineScope) {
    companion object {
        fun getInstance(project: Project) = project.service<OTelResourceManager>()
        private val LOG = logger<OTelResourceManager>()
    }

    private val metricFlow = MutableSharedFlow<RdOtelResourceMetrics>(
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        extraBufferCapacity = 100
    )

    init {
        scope.launch {
            metricFlow.collect { handleMetrics(it) }
        }
    }

    suspend fun subscribeToOTelData(
        aspireHostConfig: AspireHostProjectConfig,
        sessionHostModel: AspireSessionHostModel
    ) {
        val oTelDataSubscriptionLifetime = aspireHostConfig.aspireHostLifetime.createNested()

        withContext(Dispatchers.EDT) {
            sessionHostModel.metricReceived.advise(oTelDataSubscriptionLifetime) {
                metricFlow.tryEmit(it)
            }
        }
    }

    private suspend fun handleMetrics(metrics: RdOtelResourceMetrics) {
        LOG.trace("Metrics received $metrics")
    }
}