package dev.dukendev.watchface.binaryradar

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.graphics.toRect
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.RenderParameters
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.complications.rendering.CanvasComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleSetting
import dev.dukendev.watchface.binaryradar.data.watchface.ColorStyleIdAndResourceIds
import dev.dukendev.watchface.binaryradar.data.watchface.WatchFaceColorPalette
import dev.dukendev.watchface.binaryradar.data.watchface.WatchFaceData
import dev.dukendev.watchface.binaryradar.utils.COLOR_STYLE_SETTING
import dev.dukendev.watchface.binaryradar.utils.DRAW_HOUR_PIPS_STYLE_SETTING
import dev.dukendev.watchface.binaryradar.utils.WATCH_HAND_LENGTH_STYLE_SETTING
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Timer
import java.util.TimerTask
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


private const val FRAME_PERIOD_MS_DEFAULT: Long = 1000L

/**
 * Renders watch face via data in Room database. Also, updates watch face state based on setting
 * changes by user via [userStyleRepository.addUserStyleListener()].
 */
class DigitalWatchCanvasRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    private val complicationSlotsManager: ComplicationSlotsManager,
    currentUserStyleRepository: CurrentUserStyleRepository,
    canvasType: Int
) : Renderer.CanvasRenderer2<DigitalWatchCanvasRenderer.AnalogSharedAssets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    canvasType,
    FRAME_PERIOD_MS_DEFAULT,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false
) {
    class AnalogSharedAssets : SharedAssets {
        override fun onDestroy() {
        }
    }


    private var selectedBoxes = MutableStateFlow<List<Pair<Int, Int>>>(emptyList())
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Represents all data needed to render the watch face. All value defaults are constants. Only
    // three values are changeable by the user (color scheme, ticks being rendered, and length of
    // the minute arm). Those dynamic values are saved in the watch face APIs and we update those
    // here (in the renderer) through a Kotlin Flow.
    private var watchFaceData: WatchFaceData = WatchFaceData()

    // Converts resource ids into Colors and ComplicationDrawable.
    private var watchFaceColors = WatchFaceColorPalette.convertToWatchFaceColorPalette(
        context,
        watchFaceData.activeColorStyle,
        watchFaceData.ambientColorStyle
    )

    // Changed when setting changes cause a change in the minute hand arm (triggered by user in
    // updateUserStyle() via userStyleRepository.addUserStyleListener()).
    private var armLengthChangedRecalculateClockHands: Boolean = false


    init {
        scope.launch {
            startUpdatingGridSelection()
            currentUserStyleRepository.userStyle.collect { userStyle ->
                updateWatchFaceData(userStyle)
            }
        }
    }

    override suspend fun createSharedAssets(): AnalogSharedAssets {
        return AnalogSharedAssets()
    }

    private lateinit var timerJob: Job
    private fun startUpdatingGridSelection() {
        timerJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                selectedBoxes.update {
                    calculateGridSelection()
                }
                delay(1000)
            }
        }
    }

    override fun onRenderParametersChanged(renderParameters: RenderParameters) {
        super.onRenderParametersChanged(renderParameters)
        if (renderParameters.drawMode == DrawMode.AMBIENT) {
            selectedBoxes.update {
                calculateGridSelection().filter { it.first < 5 }
            }
        }
    }

    private fun calculateGridSelection(): List<Pair<Int, Int>> {

        val calendar = Calendar.getInstance()
        val hours = (calendar.get(Calendar.HOUR_OF_DAY)) % 24
        val minutes = (calendar.get(Calendar.MINUTE)) % 60
        val seconds = (calendar.get(Calendar.SECOND))

        val binarySeconds =
            seconds.toString().toCharArray().toList().map(Character::getNumericValue)
        val binaryMinutes =
            minutes.toString().toCharArray().toList().map(Character::getNumericValue)
        val binaryHours = hours.toString().toCharArray().toList().map(Character::getNumericValue)
        Log.d("active", "$hours:$minutes:$seconds = $binaryHours $binaryMinutes $binarySeconds")
        val selection = mutableListOf<Pair<Int, Int>>()

        binarySeconds.checkSelection(5) { i, j ->
            selection.add(Pair(i, j))
        }
        binaryMinutes.checkSelection(3) { i, j ->
            selection.add(Pair(i, j))
        }

        binaryHours.checkSelection(1) { i, j ->
            selection.add(Pair(i, j))
        }
        Log.d("active", selection.toString())
        return selection.sortedByDescending { it.first }
            .map { Pair(it.first + 1, backwardRotateBy2(it.second)) }
    }


    private fun List<Int>.checkSelection(outerCircle: Int, onSelection: (Int, Int) -> Unit) {
        val operation: MutableList<Int> = mutableListOf()
        if (this.size == 1) {
            operation.add(0)
        }

        operation.addAll(this)


        operation.forEachIndexed { index, num ->
            val binaryEncoding =
                num.toString(2).padStart(4, '0').toCharArray().toList()
                    .map(Character::getNumericValue)

            Log.d("active", "$num to binary $binaryEncoding")
            binaryEncoding.reversed().forEachIndexed { pos, bin ->
                if (bin == 1) {
                    val x = outerCircle - (if (index == 0) 1 else 0)
                    onSelection(x, pos)
                }
            }
        }
    }


    /*
     * Triggered when the user makes changes to the watch face through the settings activity. The
     * function is called by a flow.
     */
    private fun updateWatchFaceData(userStyle: UserStyle) {
        Log.d(TAG, "updateWatchFace(): $userStyle")

        var newWatchFaceData: WatchFaceData = watchFaceData

        // Loops through user style and applies new values to watchFaceData.
        for (options in userStyle) {
            when (options.key.id.toString()) {
                COLOR_STYLE_SETTING -> {
                    val listOption = options.value as
                        UserStyleSetting.ListUserStyleSetting.ListOption

                    newWatchFaceData = newWatchFaceData.copy(
                        activeColorStyle = ColorStyleIdAndResourceIds.getColorStyleConfig(
                            listOption.id.toString()
                        )
                    )
                }

                DRAW_HOUR_PIPS_STYLE_SETTING -> {
                    val booleanValue = options.value as
                        UserStyleSetting.BooleanUserStyleSetting.BooleanOption

                    newWatchFaceData = newWatchFaceData.copy(
                        drawHourPips = booleanValue.value
                    )
                }

                WATCH_HAND_LENGTH_STYLE_SETTING -> {
                    val doubleValue = options.value as
                        UserStyleSetting.DoubleRangeUserStyleSetting.DoubleRangeOption

                    // The arm lengths are usually only calculated the first time the watch face is
                    // loaded to reduce the ops in the onDraw(). Because we updated the minute hand
                    // watch length, we need to trigger a recalculation.
                    armLengthChangedRecalculateClockHands = true

                    // Updates length of minute hand based on edits from user.
                    val newMinuteHandDimensions = newWatchFaceData.minuteHandDimensions.copy(
                        lengthFraction = doubleValue.value.toFloat()
                    )

                    newWatchFaceData = newWatchFaceData.copy(
                        minuteHandDimensions = newMinuteHandDimensions
                    )
                }
            }
        }

        // Only updates if something changed.
        if (watchFaceData != newWatchFaceData) {
            watchFaceData = newWatchFaceData

            // Recreates Color and ComplicationDrawable from resource ids.
            watchFaceColors = WatchFaceColorPalette.convertToWatchFaceColorPalette(
                context,
                watchFaceData.activeColorStyle,
                watchFaceData.ambientColorStyle
            )

            // Applies the user chosen complication color scheme changes. ComplicationDrawables for
            // each of the styles are defined in XML so we need to replace the complication's
            // drawables.
            for ((_, complication) in complicationSlotsManager.complicationSlots) {
                ComplicationDrawable.getDrawable(
                    context,
                    watchFaceColors.complicationStyleDrawableId
                )?.let {
                    (complication.renderer as CanvasComplicationDrawable).drawable = it
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        scope.cancel("AnalogWatchCanvasRenderer scope clear() request")
        timerJob.cancel("Binary watch face service not active")
        super.onDestroy()
    }

    override fun renderHighlightLayer(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: AnalogSharedAssets
    ) {
        canvas.drawColor(renderParameters.highlightLayer!!.backgroundTint)

        for ((_, complication) in complicationSlotsManager.complicationSlots) {
            if (complication.enabled) {
                complication.renderHighlightLayer(canvas, zonedDateTime, renderParameters)
            }
        }
    }

    override fun render(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: AnalogSharedAssets
    ) {
        val backgroundColor = if (renderParameters.drawMode == DrawMode.AMBIENT) {
            watchFaceColors.ambientBackgroundColor
        } else {
            watchFaceColors.activeBackgroundColor
        }
        canvas.drawColor(backgroundColor)
        val numCircles = 7
        if (renderParameters.drawMode == DrawMode.AMBIENT) {
            val timer = Timer()
            timer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    selectedBoxes.update {
                        calculateGridSelection().filter { it.first < 5 }
                    }
                }
            }, 0, 10 * 1000L)
        }

        drawDarkThemeRadialGrid(canvas, bounds, numCircles)
        scope.launch {
            selectedBoxes.onEach { boxes ->
                boxes.sortedBy { it.first }
            }.conflate().collectLatest {
                paintSelectedBoxes(canvas, bounds, 7, 4, it)
                delay(1000)
            }
        }

        drawDarkThemeRadialGrid(canvas, bounds, numCircles)

//        //complication
//        drawComplications(canvas, zonedDateTime)
    }

    private fun backwardRotateBy2(number: Int): Int {
        if (number in 0..3) {
            return (number - 2 + 4) % 4
        } else {
            throw IllegalArgumentException("Input number must be in the range 0..3")
        }
    }

    // ----- All drawing functions -----
    private fun drawComplications(canvas: Canvas, zonedDateTime: ZonedDateTime) {
        for ((_, complication) in complicationSlotsManager.complicationSlots) {
            if (complication.enabled) {
                complication.render(canvas, zonedDateTime, renderParameters)
            }
        }
    }

    private fun drawDarkThemeRadialGrid(
        canvas: Canvas,
        bounds: Rect,
        numCircles: Int,
        numCuts: Int = 4,
        color: Int = Color.DKGRAY,
        isRemove: Boolean = false
    ) {

        val backgroundColor = if (renderParameters.drawMode == DrawMode.AMBIENT) {
            watchFaceColors.ambientBackgroundColor
        } else {
            watchFaceColors.activeBackgroundColor
        }
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()
        val maxRadius = min(bounds.width(), bounds.height()) / 2
        val circleRadiusStep = maxRadius / numCircles
        val cutAngle = 360f / numCuts
        val paint = Paint()

        // Draw the concentric circles with white borders
        for (circleIndex in 0 until numCircles) {
            val circleRadius = circleRadiusStep * circleIndex

            val oval = RectF(
                centerX - circleRadius,
                centerY - circleRadius,
                centerX + circleRadius,
                centerY + circleRadius
            )
            if (isRemove) {
                // Draw the radial grid
                paint.color = backgroundColor
                paint.style = Paint.Style.FILL
                canvas.drawArc(oval, 0f, 360f, false, paint)
            }
            val glowingPaint = Paint()
            glowingPaint.isAntiAlias = true
            glowingPaint.color = Color.parseColor("#5465ff")
            glowingPaint.maskFilter = BlurMaskFilter(
                2f,
                BlurMaskFilter.Blur.NORMAL
            )
            glowingPaint.strokeWidth = 2f
            glowingPaint.style = Paint.Style.STROKE
            // Draw the radial grid
            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f

            val strokeColor =
                if (renderParameters.drawMode == DrawMode.AMBIENT) paint else glowingPaint

            canvas.drawArc(oval, 0f, 360f, false, strokeColor)



            for (cutIndex in 0 until numCuts) {
                val cutStartAngle = cutAngle * cutIndex
                canvas.drawLine(
                    centerX,
                    centerY,
                    centerX + circleRadius * cos(Math.toRadians(cutStartAngle.toDouble())).toFloat(),
                    centerY + circleRadius * sin(Math.toRadians(cutStartAngle.toDouble())).toFloat(),
                    strokeColor
                )
            }
        }
    }

    private fun paintSelectedBoxes(
        canvas: Canvas,
        bounds: Rect,
        numCircles: Int,
        numCuts: Int,
        selectedBoxes: List<Pair<Int, Int>>
    ) {
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()
        val maxRadius = min(bounds.width(), bounds.height()) / 2
        val circleRadiusStep = maxRadius / numCircles
        val cutAngle = 360f / numCuts
        val paint = Paint()

        for ((circleIndex, quadrantIndex) in selectedBoxes) {
            val circleRadius = circleRadiusStep * circleIndex
            val cutStartAngle = (quadrantIndex * cutAngle) + 90f // Offset by 90 degrees
            val cutEndAngle = cutStartAngle + cutAngle

            val colors = when (circleIndex) {
                in 1..2 -> intArrayOf(Color.parseColor("#5465ff"), Color.parseColor("#00d4ff"))
                in 3..4 -> intArrayOf(Color.parseColor("#00d4ff"), Color.parseColor("#5465ff"))
                in 5..6 -> intArrayOf(Color.parseColor("#362E78"), Color.parseColor("#a09be7"))

                else -> intArrayOf(
                    Color.GRAY,
                    Color.GRAY
                ) // Fallback color if the index is out of range
            }


            val positions = floatArrayOf(0.0f, 1.0f)

            val shader = LinearGradient(
                centerX - circleRadius, centerY, centerX + circleRadius, centerY,
                colors, positions, Shader.TileMode.CLAMP
            )

            paint.shader = shader
            paint.style = Paint.Style.FILL

            if (renderParameters.drawMode == DrawMode.AMBIENT) {
                paint.alpha = 180
                paint.shader = LinearGradient(
                    centerX - circleRadius, centerY, centerX + circleRadius, centerY,
                    listOf(
                        watchFaceColors.ambientPrimaryColor,
                        watchFaceColors.ambientSecondaryColor
                    ).toIntArray(), positions, Shader.TileMode.CLAMP
                )
            }

            val oval = RectF(
                centerX - circleRadius,
                centerY - circleRadius,
                centerX + circleRadius,
                centerY + circleRadius
            )

            val path = Path()
            path.arcTo(oval, cutStartAngle, cutAngle)
            path.lineTo(centerX, centerY)
            path.close()

            canvas.drawPath(path, paint)

            if (circleIndex > 0) {
                drawDarkThemeRadialGrid(
                    canvas,
                    oval.toRect(),
                    circleIndex,
                    numCuts,
                    Color.DKGRAY,
                    true
                )
            }
        }
    }

    companion object {
        private const val TAG = "digitalWF"
    }
}
