package me.rafaelldi.aspire.otel

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.jetbrains.rd.util.addUnique
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.reactive.AddRemove
import me.rafaelldi.aspire.generated.AspireSessionHostModel
import me.rafaelldi.aspire.generated.ResourceMetric
import me.rafaelldi.aspire.generated.ResourceMetricId
import me.rafaelldi.aspire.run.AspireHostConfig
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString

@Service(Service.Level.PROJECT)
class OTelService(private val project: Project) {
    companion object {
        fun getInstance(project: Project) = project.service<OTelService>()

        private val LOG = logger<OTelService>()
    }

    private val oTelServers = ConcurrentHashMap<String, AspireSessionHostModel>()

    fun addOTelServer(
        key: String,
        sessionHostModel: AspireSessionHostModel
    ) {
        if (oTelServers.containsKey(key)) return

        LOG.trace("Saving OpenTelemetry host for $key")
        oTelServers[key] = sessionHostModel
    }

    fun subscribeToMetricIds(
        key: String,
        resourceId: String,
        lifetime: Lifetime,
        subscribeAction: (MetricId) -> Unit
    ) {
        val model = oTelServers[key] ?: return
        model.metricIds.adviseAddRemove(lifetime) { action, _, metricId ->
            when (action) {
                AddRemove.Add -> {
                    if (!metricId.resourceId.equals(resourceId, true)) return@adviseAddRemove
                    subscribeAction(MetricId(metricId.scopeName, metricId.metricName))
                }

                AddRemove.Remove -> {}
            }
        }
    }

    fun subscribeToMetricValues(
        key: String,
        resourceId: String,
        metricId: MetricId,
        lifetime: Lifetime,
        subscribeAction: (ResourceMetric) -> Unit
    ) {
        val model = oTelServers[key] ?: return
        val resourceMetricId = ResourceMetricId(resourceId, metricId.scopeName, metricId.metricName)
        model.metricReceived.advise(lifetime) {
            if (it.id != resourceMetricId) return@advise
            subscribeAction(it)
        }
        model.metricSubscriptions.addUnique(lifetime, resourceMetricId)
    }
}