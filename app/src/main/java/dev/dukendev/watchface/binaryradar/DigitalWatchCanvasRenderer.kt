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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toRect
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
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
import androidx.wear.watchface.style.WatchFaceLayer
import dev.dukendev.watchface.binaryradar.data.watchface.ColorStyleIdAndResourceIds
import dev.dukendev.watchface.binaryradar.data.watchface.WatchFaceColorPalette
import dev.dukendev.watchface.binaryradar.data.watchface.WatchFaceData
import dev.dukendev.watchface.binaryradar.utils.COLOR_STYLE_SETTING
import dev.dukendev.watchface.binaryradar.utils.DRAW_HOUR_PIPS_STYLE_SETTING
import dev.dukendev.watchface.binaryradar.utils.WATCH_HAND_LENGTH_STYLE_SETTING
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import kotlin.math.min
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
//            setupTimerUpdate()
//            startUpdatingGridSelection()
            currentUserStyleRepository.userStyle.collect { userStyle ->
                updateWatchFaceData(userStyle)
            }
        }
    }

    override suspend fun createSharedAssets(): AnalogSharedAssets {
        return AnalogSharedAssets()
    }

    private fun setupTimerUpdate() {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            selectedBoxes.update {
                calculateGridSelection()
            }
        }, 1000L)
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

    private var dateText: String = ""

    override fun onRenderParametersChanged(renderParameters: RenderParameters) {
        super.onRenderParametersChanged(renderParameters)
        if (renderParameters.drawMode == DrawMode.AMBIENT) {
            selectedBoxes.update {
                calculateGridSelection().filter { it.first < 5 }
            }
        }
        val cal = Calendar.getInstance()
        val dayFormat = SimpleDateFormat("EEEE", Locale.getDefault())
        dateText = dayFormat.format(cal.time)
    }

    private val isSecondsGridEnabled = false

    private fun calculateGridSelection(): List<Pair<Int, Int>> {

        val calendar = Calendar.getInstance()
        val hours = (calendar.get(Calendar.HOUR_OF_DAY)) % 12
        val minutes = (calendar.get(Calendar.MINUTE)) % 60
        val seconds = (calendar.get(Calendar.SECOND))

        val binarySeconds =
            seconds.toString().toCharArray().toList().map(Character::getNumericValue)
        val binaryMinutes =
            minutes.toString().toCharArray().toList().map(Character::getNumericValue)
        val binaryHours = hours.toString().toCharArray().toList().map(Character::getNumericValue)
        Log.d("active", "$hours:$minutes:$seconds = $binaryHours $binaryMinutes $binarySeconds")
        val selection = mutableListOf<Pair<Int, Int>>()

        if (isSecondsGridEnabled) {
            binarySeconds.checkSelection(5) { i, j ->
                selection.add(Pair(i, j))
            }
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

        drawTextComponents(canvas, bounds)


        val numCircles = 7
        if (renderParameters.drawMode == DrawMode.AMBIENT) {
            val timer = Timer()
            timer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    selectedBoxes.update {
                        calculateGridSelection().filter { it.first < 5 }
                    }
                }
            }, 0, 15 * 1000L)
        }
        if (renderParameters.drawMode == DrawMode.INTERACTIVE || renderParameters.drawMode == DrawMode.LOW_BATTERY_INTERACTIVE) {
            selectedBoxes.update {
                calculateGridSelection()
            }
        }
        scope.launch {
            selectedBoxes.onEach { boxes ->
                boxes.sortedBy { it.first }
            }.conflate().collectLatest {
                paintSelectedBoxes(canvas, bounds, 7, 4, it)
                delay(1000)
            }
        }
        drawDarkThemeRadialGrid(canvas, bounds, numCircles)
        drawAxis(canvas, bounds)

        drawClockHands(canvas, bounds, zonedDateTime)
    }


    private fun drawTextComponents(canvas: Canvas, bounds: Rect) {

        //draw text
        val calendar = Calendar.getInstance()
        val dayOfWeekFormat = SimpleDateFormat("EEEE")
        val dateFormat = SimpleDateFormat("dd:MMMM")
        val dateDayMonth = dateFormat.format(calendar.time)
        val dayOfWeek = dayOfWeekFormat.format(calendar.time)
        val titlePaint = Paint()
        titlePaint.color = Color.parseColor("#ffffff")
        titlePaint.isAntiAlias = true
        val typeface = ResourcesCompat.getFont(context, R.font.ubuntu_mono_bold)
        titlePaint.typeface = typeface; titlePaint.letterSpacing = 0.02F
        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.textSize = 18f
        val path = Path()
        path.addArc(
            RectF(bounds.left + 5f, bounds.top + 5f, bounds.right - 5f, bounds.bottom - 5f),
            105f,
            -30f
        )
        canvas.drawTextOnPath(dayOfWeek, path, 0f, -8f, titlePaint)


        val datePath = Path()
        datePath.addArc(
            RectF(bounds.left + 5f, bounds.top + 5f, bounds.right - 5f, bounds.bottom - 5f),
            -110f,
            35f
        )
        val (day, month) = dateDayMonth.split(":")
        val dateText = "$month , ${day.toInt().addSuffix()}"

        canvas.drawTextOnPath(dateText, datePath, 0f, 16f, titlePaint)
    }

    private var currentWatchFaceSize = Rect(0, 0, 0, 0)

    private val clockHandPaint = Paint().apply {
        isAntiAlias = true
        strokeWidth =
            context.resources.getDimensionPixelSize(R.dimen.clock_hand_stroke_width).toFloat()
    }

    private lateinit var secondHand: Path

    private fun recalculateClockHands(bounds: Rect) {
        Log.d(TAG, "recalculateClockHands()")
        secondHand =
            createClockHand(
                bounds,
                watchFaceData.secondHandDimensions.lengthFraction,
                watchFaceData.secondHandDimensions.widthFraction,
                watchFaceData.gapBetweenHandAndCenterFraction,
                watchFaceData.secondHandDimensions.xRadiusRoundedCorners,
                watchFaceData.secondHandDimensions.yRadiusRoundedCorners
            )
    }

    private fun drawClockHands(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime
    ) {
        // Only recalculate bounds (watch face size/surface) has changed or the arm of one of the
        // clock hands has changed (via user input in the settings).
        // NOTE: Watch face surface usually only updates one time (when the size of the device is
        // initially broadcasted).
        if (currentWatchFaceSize != bounds || armLengthChangedRecalculateClockHands) {
            armLengthChangedRecalculateClockHands = false
            currentWatchFaceSize = bounds
            recalculateClockHands(bounds)
        }

        // Retrieve current time to calculate location/rotation of watch arms.
        val secondOfDay = zonedDateTime.toLocalTime().toSecondOfDay()
        canvas.withScale(
            x = WATCH_HAND_SCALE,
            y = WATCH_HAND_SCALE,
            pivotX = bounds.exactCenterX(),
            pivotY = bounds.exactCenterY()
        ) {
            val drawAmbient = renderParameters.drawMode == DrawMode.AMBIENT
            val positions = floatArrayOf(0.0f, 1.0f)
            val shader = LinearGradient(
                centerX - WATCH_HAND_SCALE,
                centerY,
                centerX + WATCH_HAND_SCALE,
                centerY,
                intArrayOf(Color.parseColor("#362E78"), Color.parseColor("#a09be7")),
                positions,
                Shader.TileMode.CLAMP
            )
            clockHandPaint.style = Paint.Style.FILL
            clockHandPaint.shader = shader
            if (!drawAmbient) {
                val secondsPerSecondHandRotation = Duration.ofMinutes(1).seconds
                val secondsRotation = secondOfDay.rem(secondsPerSecondHandRotation) * 360.0f /
                    secondsPerSecondHandRotation
                withRotation(secondsRotation, bounds.exactCenterX(), bounds.exactCenterY()) {
                    drawPath(secondHand, clockHandPaint)
                }
            }
        }
    }


    /**
     * Returns a round rect clock hand if {@code rx} and {@code ry} equals to 0, otherwise return a
     * rect clock hand.
     *
     * @param bounds The bounds use to determine the coordinate of the clock hand.
     * @param length Clock hand's length, in fraction of {@code bounds.width()}.
     * @param thickness Clock hand's thickness, in fraction of {@code bounds.width()}.
     * @param gapBetweenHandAndCenter Gap between inner side of arm and center.
     * @param roundedCornerXRadius The x-radius of the rounded corners on the round-rectangle.
     * @param roundedCornerYRadius The y-radius of the rounded corners on the round-rectangle.
     */
    private fun createClockHand(
        bounds: Rect,
        length: Float,
        thickness: Float,
        gapBetweenHandAndCenter: Float,
        roundedCornerXRadius: Float,
        roundedCornerYRadius: Float
    ): Path {
        val width = bounds.width()
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val left = centerX - thickness / 2 * width
        val top = centerY - (gapBetweenHandAndCenter + length) * width
        val right = centerX + thickness / 2 * width
        val bottom = centerY - gapBetweenHandAndCenter * width
        val path = Path()

        if (roundedCornerXRadius != 0.0f || roundedCornerYRadius != 0.0f) {
            path.addRoundRect(
                left,
                top,
                right,
                bottom,
                roundedCornerXRadius,
                roundedCornerYRadius,
                Path.Direction.CW
            )
        } else {
            path.addRect(
                left,
                top,
                right,
                bottom,
                Path.Direction.CW
            )
        }
        return path
    }


    private fun Int.addSuffix(): String {
        return when {
            this % 10 == 1 -> "$this st"
            this % 10 == 2 -> "$this nd"
            this % 10 == 3 -> "$this rd"
            else -> "$this th"
        }
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
                paint.strokeWidth = 0.5f
                paint.color = backgroundColor
                paint.style = Paint.Style.FILL
                canvas.drawArc(oval, 0f, 360f, false, paint)
            }
            val glowingPaint = Paint()
            glowingPaint.isAntiAlias = true
            glowingPaint.color = Color.parseColor("#5465ff")
            glowingPaint.maskFilter = BlurMaskFilter(
                1f,
                BlurMaskFilter.Blur.NORMAL
            )
            glowingPaint.strokeWidth = 1f
            glowingPaint.style = Paint.Style.STROKE
            // Draw the radial grid
            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f

            val strokeColor =
                if (renderParameters.drawMode == DrawMode.AMBIENT) paint else glowingPaint


            if (isRemove) {
                strokeColor.strokeWidth = 0.5f
            }
            canvas.drawArc(oval, 0f, 360f, false, strokeColor)
        }
    }

    private fun drawAxis(canvas: Canvas, bounds: Rect, isRemove: Boolean = false) {


        val maxRadius = min(bounds.width(), bounds.height()) / 2
        val circleRadiusStep = maxRadius / 7

        val glowingPaint = Paint()
        glowingPaint.isAntiAlias = true
        glowingPaint.color = Color.parseColor("#5465ff")
        glowingPaint.maskFilter = BlurMaskFilter(
            1f,
            BlurMaskFilter.Blur.NORMAL
        )
        glowingPaint.strokeWidth = 0.5f
        glowingPaint.style = Paint.Style.STROKE
        //
        val paint = Paint()
        paint.color = Color.DKGRAY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.5f
        val strokeColor =
            if (renderParameters.drawMode == DrawMode.AMBIENT) paint else glowingPaint

        if (isRemove) {
            strokeColor.strokeWidth = 0.5f
        }
        canvas.drawLine(
            bounds.left.toFloat() + circleRadiusStep,
            bounds.exactCenterY(),
            bounds.right.toFloat() - circleRadiusStep,
            bounds.exactCenterY(),
            strokeColor
        )

        canvas.drawLine(
            bounds.exactCenterX(),
            bounds.top.toFloat() + circleRadiusStep,
            bounds.exactCenterX(),
            bounds.bottom.toFloat() - circleRadiusStep,
            strokeColor
        )
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
                paint.alpha = 210
                paint.shader = LinearGradient(
                    centerX - circleRadius, centerY, centerX + circleRadius, centerY,
                    listOf(
                        watchFaceColors.ambientSecondaryColor,
                        watchFaceColors.ambientPrimaryColor
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
        private const val WATCH_HAND_SCALE = 1.0f
    }
}
