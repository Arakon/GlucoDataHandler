package de.michelinside.glucodatahandler.common.chart

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.core.view.drawToBitmap
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.utils.Utils as ChartUtils
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.R
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.notifier.InternalNotifier
import de.michelinside.glucodatahandler.common.notifier.NotifierInterface
import de.michelinside.glucodatahandler.common.notifier.NotifySource
import de.michelinside.glucodatahandler.common.utils.DummyGraphData
import de.michelinside.glucodatahandler.common.utils.Utils
import java.util.concurrent.TimeUnit


open class ChartCreator(protected val chart: LineChart, protected val context: Context): NotifierInterface,
    SharedPreferences.OnSharedPreferenceChangeListener {
    private val LOG_ID = "GDH.Chart.Creator"
    protected var created = false
    protected val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
    protected var init = false
    protected var hasTimeNotifier = false

    private val demoMode = false

    private var graphPrefList = mutableSetOf(
        Constants.SHARED_PREF_LOW_GLUCOSE,
        Constants.SHARED_PREF_HIGH_GLUCOSE,
        Constants.SHARED_PREF_TARGET_MIN,
        Constants.SHARED_PREF_TARGET_MAX,
        Constants.SHARED_PREF_COLOR_ALARM,
        Constants.SHARED_PREF_COLOR_OUT_OF_RANGE,
        Constants.SHARED_PREF_COLOR_OK,
        Constants.SHARED_PREF_SHOW_OTHER_UNIT
    )

    private fun init() {
        if(!init) {
            Log.d(LOG_ID, "init")
            ChartUtils.init(context)
            sharedPref.registerOnSharedPreferenceChangeListener(this)
            if(!demoMode)
                updateNotifier()
        }
    }

    protected fun updateNotifier() {
        Log.d(LOG_ID, "updateNotifier - has data: ${ChartData.hasData(getMinTime())} - xAxisEnabled: ${chart.xAxis.isEnabled}")
        if(ChartData.hasData(getMinTime()) || chart.xAxis.isEnabled) {
            InternalNotifier.addNotifier(context, this, mutableSetOf(NotifySource.MESSAGECLIENT, NotifySource.BROADCAST, NotifySource.TIME_VALUE))
            hasTimeNotifier = true
        } else {
            InternalNotifier.addNotifier(context, this, mutableSetOf(NotifySource.MESSAGECLIENT, NotifySource.BROADCAST))
            hasTimeNotifier = false
        }
    }

    fun create() {
        Log.d(LOG_ID, "create")
        try {
            init()
            resetChart()
            initXaxis()
            initYaxis()
            initChart()
            if(!demoMode)
                initData()
            created = true
            if(demoMode)
                demo()
        } catch (exc: Exception) {
            Log.e(LOG_ID, "create exception: " + exc.message.toString() )
        }
    }

    fun close() {
        Log.d(LOG_ID, "close")
        if(init) {
            InternalNotifier.remNotifier(context, this)
            sharedPref.unregisterOnSharedPreferenceChangeListener(this)
            init = false
        }

    }

    fun isRight(): Boolean {
        return chart.highestVisibleX.toInt() == chart.xChartMax.toInt()
    }

    fun isLeft(): Boolean {
        return chart.lowestVisibleX.toInt() == chart.xChartMin.toInt()
    }

    private fun resetChart() {
        Log.v(LOG_ID, "resetChart")
        //chart.highlightValue(null)
        chart.fitScreen()
        chart.data?.clearValues()
        chart.axisRight.removeAllLimitLines()
        chart.xAxis.valueFormatter = null
        chart.axisRight.valueFormatter = null
        chart.axisLeft.valueFormatter = null
        chart.marker = null
        chart.notifyDataSetChanged()
        chart.clear()
        chart.invalidate()
    }

    protected open fun initXaxis() {
        Log.v(LOG_ID, "initXaxis")
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.setDrawGridLines(true)
        chart.xAxis.enableGridDashedLine(10F, 10F, 0F)
        chart.xAxis.valueFormatter = TimeValueFormatter(chart)
        chart.setXAxisRenderer(TimeAxisRenderer(chart))
        chart.xAxis.textColor = context.resources.getColor(R.color.text_color)
    }

    protected open fun initYaxis() {
        Log.v(LOG_ID, "initYaxis")
        chart.axisRight.valueFormatter = GlucoseFormatter()
        chart.axisRight.setDrawZeroLine(false)
        chart.axisRight.setDrawAxisLine(false)
        chart.axisRight.setDrawGridLines(false)
        chart.axisRight.textColor = context.resources.getColor(R.color.text_color)
        chart.axisLeft.isEnabled = showOtherUnit()
        if(chart.axisLeft.isEnabled) {
            chart.axisLeft.valueFormatter = GlucoseFormatter(true)
            chart.axisLeft.setDrawZeroLine(false)
            chart.axisLeft.setDrawAxisLine(false)
            chart.axisLeft.setDrawGridLines(false)
            chart.axisLeft.textColor = context.resources.getColor(R.color.text_color)
        }
    }

    private fun createLimitLine(limit: Float): LimitLine {
        val line = LimitLine(limit)
        line.lineColor = context.resources.getColor(R.color.gray)
        return line
    }

    protected fun initDataSet() {
        if(chart.data == null || chart.data.entryCount == 0) {
            Log.v(LOG_ID, "initDataSet")
            val dataSet = LineDataSet(ArrayList(), "Glucose Values")
            //dataSet.valueFormatter = GlucoseFormatter()
            //dataSet.colors = mutableListOf<Int>()
            dataSet.circleColors = mutableListOf<Int>()
            //dataSet.lineWidth = 1F
            dataSet.circleRadius = 3F
            dataSet.setDrawValues(false)
            dataSet.setDrawCircleHole(false)
            dataSet.axisDependency = YAxis.AxisDependency.RIGHT
            dataSet.enableDashedLine(0F, 1F, 0F)
            chart.data = LineData(dataSet)
        }
    }

    protected open fun initChart(touchEnabled: Boolean = true) {
        Log.v(LOG_ID, "initChart - touchEnabled: $touchEnabled")
        chart.setTouchEnabled(touchEnabled)
        initDataSet()
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.isAutoScaleMinMaxEnabled = false
        chart.legend.isEnabled = false
        chart.description.isEnabled = false
        chart.isScaleYEnabled = false
        if(touchEnabled) {
            val mMarker = CustomBubbleMarker(context)
            mMarker.chartView = chart
            chart.marker = mMarker
            chart.setOnChartValueSelectedListener(object: OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry?, h: Highlight?) {
                    Log.v(LOG_ID, "onValueSelected: ${e?.x} - ${e?.y} - index: ${h?.dataSetIndex}")
                    if(h?.dataSetIndex == 1) {
                        // do not select time line
                        chart.highlightValue(null)
                    }
                }
                override fun onNothingSelected() {
                    Log.v(LOG_ID, "onNothingSelected")
                }
            })
        }
        chart.axisRight.removeAllLimitLines()

        if(ReceiveData.highRaw > 0F)
            chart.axisRight.addLimitLine(createLimitLine(ReceiveData.highRaw))
        if(ReceiveData.lowRaw > 0F)
            chart.axisRight.addLimitLine(createLimitLine(ReceiveData.lowRaw))
        if(ReceiveData.targetMinRaw > 0F)
            chart.axisRight.addLimitLine(createLimitLine(ReceiveData.targetMinRaw))
        if(ReceiveData.targetMaxRaw > 0F)
            chart.axisRight.addLimitLine(createLimitLine(ReceiveData.targetMaxRaw))
        chart.axisRight.axisMinimum = Constants.GLUCOSE_MIN_VALUE.toFloat()-10F
        chart.axisRight.axisMaximum = ReceiveData.highRaw+10
        chart.axisLeft.axisMinimum = chart.axisRight.axisMinimum
        chart.axisLeft.axisMaximum = chart.axisRight.axisMaximum
        chart.isScaleXEnabled = false
        chart.invalidate()
    }

    protected fun initData() {
        initDataSet()
        addEntries(ChartData.getData(getMinTime()))
    }

    protected open fun getMinTime(): Long {
        return 0L
    }

    protected open fun getDefaultRange(): Long {
        return 240L
    }

    protected open fun addEntries(values: ArrayList<Entry>) {
        Log.d(LOG_ID, "Add ${values.size} entries")
        val dataSet = chart.data.getDataSetByIndex(0) as LineDataSet
        var added = false
        if(values.isNotEmpty()) {
            for (i in 0 until values.size) {
                val entry = values[i]
                if (dataSet.values.isEmpty() || dataSet.values.last().x < entry.x) {
                    added = true
                    dataSet.addEntry(entry)
                    val color = ReceiveData.getValueColor(entry.y.toInt())
                    //dataSet.addColor(color)
                    dataSet.circleColors.add(color)
                    dataSet.notifyDataSetChanged()

                    if (chart.axisRight.axisMinimum > (entry.y - 10F)) {
                        chart.axisRight.axisMinimum = entry.y - 10F
                        chart.axisLeft.axisMinimum = chart.axisRight.axisMinimum
                    }
                    if (chart.axisRight.axisMaximum < (entry.y + 10F)) {
                        chart.axisRight.axisMaximum = entry.y + 10F
                        chart.axisLeft.axisMaximum = chart.axisRight.axisMaximum
                    }
                }
            }
        }

        if(added) {
            updateChart(dataSet)
        } else {
            addEmptyTimeData()
        }
    }

    protected fun addEmptyTimeData() {
        Log.v(LOG_ID, "addEmptyTimeData - entries: ${chart.data?.entryCount}")
        if(chart.data?.entryCount == 0 && !chart.xAxis.isEnabled) {
            if(hasTimeNotifier)
                updateNotifier()
            return // no data and not xAxis, do not add the time line, as it is not needed
        }
        if(!hasTimeNotifier)
            updateNotifier()
        var minTime = getMinTime()
        if (minTime == 0L) // at least one hour should be shown
            minTime = System.currentTimeMillis() - (60 * 60 * 1000L)

        var minValue = 0L
        var maxValue = 0L

        if(chart.data != null && chart.data.dataSetCount > 0) {
            val dataSet = chart.data.getDataSetByIndex(0) as LineDataSet
            if (dataSet.values.isEmpty()) {
                Log.v(LOG_ID, "No values, set min and max")
                minValue = minTime
                maxValue = System.currentTimeMillis()
            } else {
                Log.v(
                    LOG_ID,
                    "Min time: ${Utils.getUiTimeStamp(minTime)} - first value: ${
                        Utils.getUiTimeStamp(TimeValueFormatter.from_chart_x(dataSet.values.first().x).time)
                    } - last value: ${Utils.getUiTimeStamp(TimeValueFormatter.from_chart_x(dataSet.values.last().x).time)}"
                )
                if (TimeValueFormatter.from_chart_x(dataSet.values.first().x).time > minTime) {
                    minValue = minTime
                }
                if (Utils.getElapsedTimeMinute(TimeValueFormatter.from_chart_x(dataSet.values.last().x).time) >= 1) {
                    maxValue = System.currentTimeMillis()
                }
            }
        }
        if(minValue > 0 || maxValue > 0) {
            Log.w(LOG_ID, "Creating extra data set from ${Utils.getUiTimeStamp(minValue)} - ${Utils.getUiTimeStamp(maxValue)}")
            val entries = ArrayList<Entry>()
            if(minValue > 0)
                entries.add(Entry(TimeValueFormatter.to_chart_x(minValue), 120F))
            if(maxValue > 0)
                entries.add(Entry(TimeValueFormatter.to_chart_x(maxValue), 120F))

            val timeSet = LineDataSet(entries, "Time line")
            timeSet.lineWidth = 1F
            timeSet.circleRadius = 1F
            timeSet.setDrawValues(false)
            timeSet.setDrawCircleHole(false)
            timeSet.setCircleColors(0)
            timeSet.setColors(0)
            chart.data.addDataSet(timeSet)
        }
    }

    protected open fun updateChart(dataSet: LineDataSet) {
        val defaultRange = getDefaultRange()
        val right = isRight()
        val left = isLeft()
        Log.v(LOG_ID, "Min: ${chart.xAxis.axisMinimum} - visible: ${chart.lowestVisibleX} - Max: ${chart.xAxis.axisMaximum} - visible: ${chart.highestVisibleX} - isLeft: ${left} - isRight: ${right}" )
        var diffTimeMin = TimeUnit.MILLISECONDS.toMinutes(TimeValueFormatter.from_chart_x(chart.highestVisibleX).time - TimeValueFormatter.from_chart_x(chart.lowestVisibleX).time)
        if(!chart.highlighted.isNullOrEmpty() && chart.highlighted[0].dataSetIndex != 0) {
            Log.v(LOG_ID, "Unset current highlighter")
            chart.highlightValue(null)
        }
        chart.data = LineData(dataSet)
        addEmptyTimeData()
        chart.notifyDataSetChanged()
        val newDiffTime = TimeUnit.MILLISECONDS.toMinutes( TimeValueFormatter.from_chart_x(chart.xChartMax).time - TimeValueFormatter.from_chart_x(chart.lowestVisibleX).time)
        Log.d(LOG_ID, "Diff-Time: ${diffTimeMin} minutes - newDiffTime: ${newDiffTime} minutes")
        var setXRange = false
        if(!chart.isScaleXEnabled && newDiffTime >= 90) {
            Log.d(LOG_ID, "Enable X scale")
            chart.isScaleXEnabled = true
            setXRange = true
        }

        if((right && left && diffTimeMin < getDefaultRange() && newDiffTime >= defaultRange)) {
            Log.v(LOG_ID, "Set ${defaultRange/60} hours diff time")
            diffTimeMin = defaultRange
        }

        if(right) {
            if(diffTimeMin >= defaultRange || !left) {
                Log.d(LOG_ID, "Fix interval: $diffTimeMin")
                chart.setVisibleXRangeMaximum(diffTimeMin.toFloat())
                setXRange = true
            }
            Log.v(LOG_ID, "moveViewToX ${chart.xChartMax}")
            chart.moveViewToX(chart.xChartMax)
        } else {
            Log.v(LOG_ID, "Invalidate chart")
            chart.setVisibleXRangeMaximum(diffTimeMin.toFloat())
            setXRange = true
            chart.invalidate()
        }

        if(setXRange) {
            chart.setVisibleXRangeMinimum(60F)
            chart.setVisibleXRangeMaximum(60F*24F)
        }
    }

    protected open fun update() {
        // update limit lines
        if(ReceiveData.time > 0) {
            val entry = Entry(TimeValueFormatter.to_chart_x(ReceiveData.time), ReceiveData.rawValue.toFloat())
            addEntries(arrayListOf(entry))
        }
    }

    protected open fun updateTimeElapsed() {
        Log.v(LOG_ID, "update time elapsed")
        updateChart(chart.data.getDataSetByIndex(0) as LineDataSet)
    }

    override fun OnNotifyData(context: Context, dataSource: NotifySource, extras: Bundle?) {
        try {
            Log.d(LOG_ID, "OnNotifyData: $dataSource")
            if(dataSource == NotifySource.TIME_VALUE) {
                Log.d(LOG_ID, "time elapsed: ${ReceiveData.getElapsedTimeMinute()}")
                if(ReceiveData.getElapsedTimeMinute().mod(2) == 0)
                    updateTimeElapsed()
            } else {
                update()
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "OnNotifyData exception: " + exc.message.toString() + " - " + exc.stackTraceToString() )
        }
    }

    private fun demo() {
        Log.w(LOG_ID, "Start demo")
        Thread {
            try {
                val demoData = DummyGraphData.create(5, min = 40, max = 260, stepMinute = 5)
                Log.w(LOG_ID, "Running demo for ${demoData.size} entries")
                demoData.forEach { (t, u) ->
                    addEntries(arrayListOf(Entry(TimeValueFormatter.to_chart_x(t), u.toFloat())))
                    Thread.sleep(1000)
                }
                Log.w(LOG_ID, "Demo finished")
                create()
            } catch (exc: Exception) {
                Log.e(LOG_ID, "demo exception: " + exc.message.toString() + " - " + exc.stackTraceToString() )
            }
        }.start()
    }

    fun getBitmap(): Bitmap? {
        try {
            if(chart.width > 0 && chart.height > 0)
                return chart.drawToBitmap()
        } catch (exc: Exception) {
            Log.e(LOG_ID, "getBitmap exception: " + exc.message.toString() )
        }
        return null
    }

    private fun showOtherUnit(): Boolean {
        return sharedPref.getBoolean(Constants.SHARED_PREF_SHOW_OTHER_UNIT, false)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        Log.d(LOG_ID, "onSharedPreferenceChanged: $key")
        try {
            if (graphPrefList.contains(key)) {
                Log.i(LOG_ID, "re create graph after settings changed for key: $key")
                ReceiveData.updateSettings(sharedPref)
                create()
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onSharedPreferenceChanged exception: " + exc.message.toString() )
        }
    }
}
