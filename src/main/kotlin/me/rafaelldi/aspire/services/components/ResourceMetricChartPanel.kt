@file:Suppress("UnstableApiUsage")

package me.rafaelldi.aspire.services.components

import com.intellij.ui.JBColor
import com.intellij.ui.charts.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import me.rafaelldi.aspire.generated.DoubleResourceMetricPoint
import me.rafaelldi.aspire.generated.LongResourceMetricPoint
import me.rafaelldi.aspire.generated.ResourceMetricDetails
import java.awt.Graphics2D
import java.awt.Paint
import java.awt.Point
import java.awt.geom.Point2D
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ResourceMetricChartPanel(
    private val metricDetails: ResourceMetricDetails,
    private val initialValue: Double
) : BorderLayoutPanel() {
    companion object {
        private const val TIME_RANGE = 60

        private val labelFormatter = DateTimeFormatter
            .ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        private val chartMargins = JBUI.insets(55, 15, 15, 15)
        private val chartColors =
            listOf(JBColor.BLUE, JBColor.GREEN, JBColor.RED, JBColor.ORANGE, JBColor.PINK, JBColor.YELLOW)

        private fun createTimeLabel(time: Long) = labelFormatter.format(Instant.ofEpochSecond(time))
        private fun createValueLabel(value: Double, label: String) = "${String.format("%.2f", value)} $label"

        private fun getXByLocation(location: Point, chart: GridChartWrapper<Long, Double>): Long? {
            val minMax = chart.findMinMax()
            if (!minMax.isInitialized) return null

            if (location.x < chart.margins.left) return null
            if (location.x > chart.width - chart.margins.right) return null

            val chartX = location.x - chart.margins.left
            val chartWidth = chart.width - (chart.margins.left + chart.margins.right)
            val xWidth = minMax.xMax - minMax.xMin
            return (chartX * xWidth) / chartWidth + minMax.xMin
        }

        private fun drawOvalPoint(point: Point2D.Double, g: Graphics2D, fillColor: Paint?, drawColor: Paint?) {
            val radius = 4
            val x = point.x.toInt() - radius
            val y = point.y.toInt() - radius
            val width = radius * 2
            val height = radius * 2

            g.paint = fillColor
            g.fillOval(x, y, width, height)

            g.paint = drawColor
            g.drawOval(x, y, width, height)
        }

        private fun drawDataLabels(
            point: Point2D.Double,
            valueLabel: String,
            timeLabel: String,
            g: Graphics2D,
            chart: GridChartWrapper<Long, Double>
        ) {
            g.color = JBColor.foreground()

            val valueLabelBounds = g.fontMetrics.getStringBounds(valueLabel, null)
            val valueLabelX = point.x.toInt() - valueLabelBounds.width.toInt() / 2
            var valueLabelY = point.y.toInt() - valueLabelBounds.height.toInt()
            if (valueLabelY < chart.margins.top + valueLabelBounds.height.toInt())
                valueLabelY = chart.margins.top + valueLabelBounds.height.toInt()
            g.drawString(valueLabel, valueLabelX, valueLabelY)

            val timeLabelBounds = g.fontMetrics.getStringBounds(timeLabel, null)
            val timeLabelX = point.x.toInt() - timeLabelBounds.width.toInt() / 2
            val timeLabelY = chart.height - chart.margins.bottom + timeLabelBounds.height.toInt()
            g.paint = chart.gridLabelColor
            g.drawString(timeLabel, timeLabelX, timeLabelY)
        }
    }

    private val chartColor = chartColors[chartColors.indices.random()]
    private lateinit var valueOverlay: ValueOverlay

    private val chart = lineChart<Long, Double> {
        ranges {
            yMin = 0.0
            yMax = initialValue + initialValue * 0.3
        }
        grid {
            xLines = generator(1L)
            xPainter {
                paintLine = (value) % 15 == 0L
                if (paintLine && valueOverlay.mouseLocation == null) {
                    label = createTimeLabel(value)
                }
            }
        }
        datasets {
            dataset {
                label = metricDetails.id.metricName
                lineColor = chartColor
                fillColor = chartColor.transparent(0.5)
                valueOverlay = ValueOverlay(this, metricDetails.unit)
                overlays = listOf(
                    TitleOverlay(metricDetails.id.metricName, metricDetails.unit, this),
                    DescriptionOverlay(metricDetails.description),
                    valueOverlay
                )
            }
        }
        borderPainted = true
        margins = chartMargins
    }

    init {
        add(chart.component)
    }

    fun update(value: Double, timestamp: Long) {
        chart.add(timestamp, value)
        chart.ranges.xMin = timestamp - TIME_RANGE
        chart.ranges.xMax = timestamp
        chart.ranges.yMax = maxOf(chart.ranges.yMax, value + value * 0.3)
        repaint()
    }

    fun update(point: LongResourceMetricPoint) {
        chart.add(point.timestamp, point.value.toDouble())
        chart.ranges.xMin = point.timestamp - TIME_RANGE
        chart.ranges.xMax = point.timestamp
        chart.ranges.yMax = maxOf(chart.ranges.yMax, point.value.toDouble() + point.value.toDouble() * 0.3)
        repaint()
    }

    fun update(point: DoubleResourceMetricPoint) {
        chart.add(point.timestamp, point.value)
        chart.ranges.xMin = point.timestamp - TIME_RANGE
        chart.ranges.xMax = point.timestamp
        chart.ranges.yMax = maxOf(chart.ranges.yMax, point.value + point.value * 0.3)
        repaint()
    }

    private class TitleOverlay(
        private val title: String,
        private val label: String,
        private val xyLineDataset: XYLineDataset<Long, Double>
    ) : Overlay<LineChart<*, *, *>>() {
        override fun paintComponent(g: Graphics2D) {
            g.color = JBColor.foreground()
            g.drawString(title, chart.margins.left, 20)

            val value = xyLineDataset.data.lastOrNull()?.y ?: 0.0
            val valueLabel = createValueLabel(value, label)
            val valueLabelWidth = g.fontMetrics.stringWidth(valueLabel)
            g.drawString(valueLabel, chart.width - chart.margins.left - valueLabelWidth, 20)
        }
    }

    private class DescriptionOverlay(
        private val description: String,
    ) : Overlay<LineChart<*, *, *>>() {
        override fun paintComponent(g: Graphics2D) {
            g.color = JBColor.foreground()
            g.drawString(description, chart.margins.left, 40)
        }
    }

    private inner class ValueOverlay(
        private val xyLineDataset: XYLineDataset<Long, Double>,
        private val label: String
    ) : Overlay<LineChart<Long, Double, *>>() {
        override fun paintComponent(g: Graphics2D) {
            val location = valueOverlay.mouseLocation ?: return
            val x = getXByLocation(location, chart) ?: return

            val minMax = chart.findMinMax()
            if (!minMax.isInitialized) return
            val dataPair = xyLineDataset.data.find { it.x == x } ?: xyLineDataset.data.first()
            val point = chart.findLocation(minMax, dataPair)

            drawOvalPoint(point, g, xyLineDataset.lineColor, chart.background)
            val valueLabel = createValueLabel(dataPair.y, label)
            val timeLabel = createTimeLabel(dataPair.x)
            drawDataLabels(point, valueLabel, timeLabel, g, chart)
        }
    }
}