/*
 *     Copyright (C) 2024 nift4
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RenderNode
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.util.SparseArray
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.TypefaceCompat
import androidx.core.text.getSpans
import androidx.core.util.forEach
import androidx.media3.common.util.Log
import androidx.preference.PreferenceManager
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.dpToPx
import org.akanework.gramophone.logic.getBooleanStrict
import org.akanework.gramophone.logic.getIntStrict
import org.akanework.gramophone.logic.hasRenderNodes
import org.akanework.gramophone.logic.ui.spans.MyForegroundColorSpan
import org.akanework.gramophone.logic.ui.spans.MyGradientSpan
import org.akanework.gramophone.logic.ui.spans.StaticLayoutBuilderCompat
import org.akanework.gramophone.logic.utils.CalculationUtils.lerp
import org.akanework.gramophone.logic.utils.CalculationUtils.lerpInv
import org.akanework.gramophone.logic.utils.Flags
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SpicyLyricsAttribution
import org.akanework.gramophone.logic.utils.SpeakerEntity
import org.akanework.gramophone.logic.utils.findBidirectionalBarriers
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.properties.Delegates

private const val TAG = "NewLyricsView"

class NewLyricsView(context: Context, attrs: AttributeSet?) : ScrollingView2(context, attrs),
    GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener {
    private val smallSizeFactor = 0.97f
    private var lyricAnimTime by Delegates.notNull<Float>()

    private val scaleInAnimTime
        get() = lyricAnimTime / 2f
    private val scaleColorInterpolator = PathInterpolator(0.4f, 0.2f, 0f, 1f)
    private val scrollInterpolator = PathInterpolator(0.4f, 0.2f, 0f, 1f)
    private val delayedInInterpolator = PathInterpolator(0.96f, 0.43f, 0.72f, 1f)
    private val delayedOutInterpolator = PathInterpolator(0.17f, 0f, -0.15f, 1f)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private lateinit var typeface: Typeface
    private val grdWidth = context.resources.getDimension(R.dimen.lyric_gradient_size)
    private val defaultTextSize = context.resources.getDimension(R.dimen.lyric_text_size)
    private val translationTextSize = context.resources.getDimension(R.dimen.lyric_tl_text_size)
    private val translationBackgroundTextSize =
        context.resources.getDimension(R.dimen.lyric_tl_bg_text_size)
    private var globalPaddingHorizontal = 28.5f.dpToPx(context)
    private var paddingVerticalTl = 2f
    private var paddingVerticalDefault = 18f
    private var depth = 15f.dpToPx(context)
    private var colorSpanPool = mutableListOf<MyForegroundColorSpan>()
    private var spForRender: Pair<IntArray, List<SbItem>>? = null
    private var spForMeasure: Pair<IntArray, List<SbItem>>? = null
    private var lyrics: SemanticLyrics? = null
    private var lyricsAttribution: SpicyLyricsAttribution? = null
    private var appleEffectsEnabled = prefs.getBooleanStrict("lyric_apple_music_effects", true)
    private var posForRender = 0uL
    lateinit var instance: Callbacks
    private val gestureDetector = GestureDetector(context, this)
    private var currentScrollTarget: Int? = null
    private data class SmoothScroll(
        val startTime: Double, val duration: Double, val start: Float,
        var target: Float, val targetIndex: Int?
    )
    private var currentSmoothScroll: SmoothScroll? = null
    private data class ScrollHandoff(val scrollY: Int, val targetIndex: Int?)
    private var pendingScrollHandoff: ScrollHandoff? = null
    private var delayedScrollAnimation: Pair<Long, Pair<Int, Int>>? = null
    private data class BackgroundTransition(val from: Float, val to: Float, val startedAt: Long)
    private val backgroundFractions = mutableMapOf<Int, Float>()
    private val backgroundTransitions = mutableMapOf<Int, BackgroundTransition>()
    private var backgroundItems: List<SbItem>? = null
    private var backgroundGroups = LyricBackgroundGroups(emptyList())
    private var backgroundStateInitialized = false
    private var backgroundTimelinePosition: ULong? = null
    private var backgroundWallTime = 0L
    private var stateOverrides = hashMapOf<Int, Float>()
    private var stateTime = 0uL
    private var isCallbackQueued = false
    private val invalidateCallback = Runnable { isCallbackQueued = false; postInvalidateOnAnimation() }
    private var defaultTextColor = 0
    private var highlightTextColor = 0
    private var highlightTlTextColor = 0
    private var customActiveColor: Int? = null
    private val defaultTextPaint = TextPaint().apply {
        color = Color.RED
    }
    private val translationTextPaint = TextPaint().apply {
        color = Color.GREEN
    }
    private val translationBackgroundTextPaint = TextPaint().apply {
        color = Color.BLUE
    }
    private var wordActiveSpan = MyForegroundColorSpan(Color.CYAN)
    private var wordActiveTlSpan = MyForegroundColorSpan(Color.CYAN)
    private var gradientSpanPool = mutableListOf<MyGradientSpan>()
    private var gradientTlSpanPool = mutableListOf<MyGradientSpan>()
    private val cachedNodes = if (hasRenderNodes()) SparseArray<RenderNode>() else null
    private val activeLyricsColor: Int
        get() = customActiveColor ?: if (appleEffectsEnabled) Color.WHITE else highlightTextColor

    private fun makeGradientSpan() =
        MyGradientSpan(grdWidth, defaultTextColor, activeLyricsColor)

    private fun makeGradientTlSpan() =
        MyGradientSpan(grdWidth, defaultTextColor, highlightTlTextColor)

    init {
        applyTypefaces()
        applySize()
        loadLyricAnimTime()
    }

    interface Callbacks {
        fun getCurrentPosition(): ULong
        fun isPlaying(): Boolean
        fun speed(): Float
        fun destroy()
    }

    fun setCustomActiveColor(color: Int?) {
        if (customActiveColor == color) return
        customActiveColor = color
        wordActiveSpan.color = activeLyricsColor
        gradientSpanPool.clear()
        repeat(3) { gradientSpanPool.add(makeGradientSpan()) }
        spForRender?.second?.forEach { item ->
            item.text.getSpans<MyGradientSpan>().forEach { item.text.removeSpan(it) }
        }
        invalidateDeeply()
    }

    fun updateTextColor(
        newColor: Int, newHighlightColor: Int, newHighlightTlColor: Int
    ) {
        var changed = false
        var changedTl = false
        if (defaultTextColor != newColor) {
            defaultTextColor = newColor
            defaultTextPaint.color = defaultTextColor
            translationTextPaint.color = defaultTextColor
            translationBackgroundTextPaint.color = defaultTextColor
            changed = true
            changedTl = true
        }
        if (highlightTextColor != newHighlightColor) {
            highlightTextColor = newHighlightColor
            wordActiveSpan.color = activeLyricsColor
            changed = true
        }
        if (highlightTlTextColor != newHighlightTlColor) {
            highlightTlTextColor = newHighlightTlColor
            wordActiveTlSpan.color = highlightTlTextColor
            changedTl = true
        }
        if (changed) {
            gradientSpanPool.clear()
            repeat(3) { gradientSpanPool.add(makeGradientSpan()) }
        }
        if (changedTl) {
            gradientTlSpanPool.clear()
            repeat(2) { gradientTlSpanPool.add(makeGradientTlSpan()) }
        }
        if (changed || changedTl) {
            spForRender?.second?.forEach {
                it.text.getSpans<MyGradientSpan>()
                    .forEach { s -> it.text.removeSpan(s) }
            }
            invalidateDeeply()
        }
    }

    fun updateTextColor(newColor: Int) {
        if (defaultTextColor != newColor) {
            defaultTextColor = newColor
            defaultTextPaint.color = defaultTextColor
            translationTextPaint.color = defaultTextColor
            translationBackgroundTextPaint.color = defaultTextColor
            gradientSpanPool.clear()
            repeat(3) { gradientSpanPool.add(makeGradientSpan()) }
            gradientTlSpanPool.clear()
            repeat(2) { gradientTlSpanPool.add(makeGradientTlSpan()) }
            spForRender?.second?.forEach {
                it.text.getSpans<MyGradientSpan>()
                    .forEach { s -> it.text.removeSpan(s) }
            }
            invalidateDeeply()
        }
    }

    fun updateHighlightColor(newHighlightColor: Int) {
        if (highlightTextColor != newHighlightColor) {
            highlightTextColor = newHighlightColor
            wordActiveSpan.color = activeLyricsColor
            gradientSpanPool.clear()
            repeat(3) { gradientSpanPool.add(makeGradientSpan()) }
            spForRender?.second?.forEach {
                it.text.getSpans<MyGradientSpan>()
                    .forEach { s -> it.text.removeSpan(s) }
            }
            invalidateDeeply()
        }
    }

    fun updateHighlightTlColor(newHighlightTlColor: Int) {
        if (highlightTlTextColor != newHighlightTlColor) {
            highlightTlTextColor = newHighlightTlColor
            wordActiveTlSpan.color = highlightTlTextColor
            gradientTlSpanPool.clear()
            repeat(2) { gradientTlSpanPool.add(makeGradientTlSpan()) }
            spForRender?.second?.forEach {
                it.text.getSpans<MyGradientSpan>()
                    .forEach { s -> it.text.removeSpan(s) }
            }
            invalidateDeeply()
        }
    }

    fun updateLyrics(parsedLyrics: SemanticLyrics?) {
        backgroundGroups = LyricBackgroundGroups((parsedLyrics as? SemanticLyrics.SyncedLyrics)?.text.orEmpty())
        spForRender = null
        spForMeasure = null
        backgroundItems = null
        backgroundFractions.clear()
        backgroundTransitions.clear()
        backgroundStateInitialized = false
        backgroundTimelinePosition = null
        requestLayout()
        lyrics = parsedLyrics
        stateOverrides.clear()
        invalidateDeeply()
    }

    fun prepareAutomixHandoffFrom(incoming: NewLyricsView) {
        if (incoming === this) return
        currentSmoothScroll = null
        delayedScrollAnimation = null
        pendingScrollHandoff = ScrollHandoff(incoming.scrollY, incoming.currentScrollTarget)
    }

    fun updateAttribution(attribution: SpicyLyricsAttribution?) {
        lyricsAttribution = attribution
        spForRender = null
        spForMeasure = null
        requestLayout()
        invalidateDeeply()
    }

    fun updateLyricPositionFromPlaybackPos() {
        val playbackPosition = instance.getCurrentPosition()
        if (playbackPosition != posForRender && lyrics is SemanticLyrics.SyncedLyrics)
            postInvalidateOnAnimation() // if not playing, might stay same
    }

    fun onPrefsChanged(key: String) {
        if (key == "lyric_no_animation") {
            loadLyricAnimTime()
            return
        }
        if (key == "lyric_text_size")
            applySize()
        if (key == "lyric_apple_music_effects") {
            appleEffectsEnabled = prefs.getBooleanStrict("lyric_apple_music_effects", true)
            wordActiveSpan.color = activeLyricsColor
            gradientSpanPool.clear()
            repeat(3) { gradientSpanPool.add(makeGradientSpan()) }
        }
        spForRender = null
        spForMeasure = null
        requestLayout()
    }

    private fun loadLyricAnimTime() {
        lyricAnimTime = if (prefs.getBooleanStrict("lyric_no_animation", false)) 0f else 650f
    }

    private fun applySize() {
        val newTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
            prefs.getIntStrict("lyric_text_size", 34).toFloat(),
            context.resources.displayMetrics)
        globalPaddingHorizontal = 28.5f.dpToPx(context) * newTextSize / defaultTextSize
        depth = 15f.dpToPx(context) * newTextSize / defaultTextSize
        paddingVerticalTl = 2f * newTextSize / defaultTextSize
        paddingVerticalDefault = 18f * newTextSize / defaultTextSize
        defaultTextPaint.textSize = newTextSize
        translationTextPaint.textSize = newTextSize * translationTextSize / defaultTextSize
        translationBackgroundTextPaint.textSize =
            newTextSize * translationBackgroundTextSize / defaultTextSize
    }

    private fun applyTypefaces() {
        typeface = ResourcesCompat.getFont(context, R.font.dongle_bold)
            ?: TypefaceCompat.create(context, null, Typeface.BOLD, false)
        defaultTextPaint.typeface = typeface
        translationTextPaint.typeface = typeface
        translationBackgroundTextPaint.typeface = typeface
    }

    internal fun updateBackgroundRows(items: List<SbItem>, position: ULong, now: Long, playing: Boolean): Boolean {
        if (backgroundItems !== items) {
            backgroundItems = items
            backgroundFractions.clear()
            backgroundTransitions.clear()
            backgroundStateInitialized = false
            backgroundTimelinePosition = null
        }
        val previous = backgroundTimelinePosition
        val wallElapsed = (now - backgroundWallTime).coerceAtLeast(0L)
        val positionDelta = if (previous != null) abs(position.toLong() - previous.toLong()) else 0L
        val seek = previous != null && (position < previous || positionDelta > wallElapsed * 4 + 500)
        var animating = false
        items.forEachIndexed { index, item ->
            val line = item.line
            if (item.speaker?.isBackground != true || line == null) return@forEachIndexed
            val target = if (backgroundGroups.visible(line, position)) 1f else 0f
            var transition = backgroundTransitions[index]
            var current = backgroundFractions[index] ?: target
            if (transition != null) {
                val progress = ((now - transition.startedAt).toFloat() /
                    BACKGROUND_ROW_ANIMATION_MS).coerceIn(0f, 1f)
                current = transition.from + (transition.to - transition.from) *
                    scaleColorInterpolator.getInterpolation(progress)
                if (progress >= 1f) {
                    backgroundTransitions.remove(index)
                    transition = null
                }
            }
            if (!backgroundStateInitialized || seek || lyricAnimTime == 0f || !playing) {
                current = target
                backgroundTransitions.remove(index)
            } else if ((transition?.to ?: current) != target) {
                transition = BackgroundTransition(current, target, now)
                backgroundTransitions[index] = transition
            }
            backgroundFractions[index] = current
            if (backgroundTransitions.containsKey(index)) animating = true
        }
        backgroundStateInitialized = true
        backgroundTimelinePosition = position
        backgroundWallTime = now
        return animating
    }

    internal fun backgroundRowFraction(index: Int, item: SbItem): Float =
        if (item.speaker?.isBackground == true && item.line != null) backgroundFractions[index] ?: 0f else 1f

    internal fun visibleContentHeight(items: List<SbItem>, globalTop: Int, globalBottom: Int): Int =
        globalTop + globalBottom + items.mapIndexed { index, item ->
            ((item.paddingTop + item.layout.height + item.paddingBottom) *
                backgroundRowFraction(index, item)).roundToInt()
        }.sum()

    override fun onDrawForChild(canvas: Canvas) {
        val playbackPosition = instance.getCurrentPosition()
        if (posForRender > playbackPosition && posForRender - playbackPosition < 1000uL)
            Log.w(
                TAG,
                "regressing position by ${posForRender - playbackPosition}ms " +
                    "from $posForRender to $playbackPosition!"
            )
        posForRender = playbackPosition
        val isPlaying = instance.isPlaying()
        if (spForRender == null) {
            requestLayout()
            return
        }
        val items = spForRender!!.second
        val now = AnimationUtils.currentAnimationTimeMillis()
        var animating = updateBackgroundRows(items, posForRender, now, isPlaying)
        val globalBottom = spForRender!!.first[4]
        updateChildHeight(visibleContentHeight(items, spForRender!!.first[2], globalBottom))
        pendingScrollHandoff?.let {
            scrollTo(0, it.scrollY)
            currentScrollTarget = it.targetIndex
            pendingScrollHandoff = null
        }
        var delayedScrollDoneForFrame = false
        val globalPaddingTop = spForRender!!.first[2]
        var heightSoFar = globalPaddingTop.toDouble()
        val scrollTargetIndex = primaryLyricScrollIndex(items.map { it.line }, posForRender)
        var scrollTargetPosition: Int? = null
        canvas.save()
        canvas.translate(globalPaddingHorizontal, globalPaddingTop.toFloat())
        val width = width - paddingLeft - paddingRight - globalPaddingHorizontal * 2
        val cat = AnimationUtils.currentAnimationTimeMillis().toDouble()
        spForRender!!.second.forEachIndexed { i, it ->
            val rowFraction = backgroundRowFraction(i, it)
            if (rowFraction <= 0f) return@forEachIndexed
            if (rowFraction < 1f) {
                canvas.save()
                canvas.clipRect(0f, 0f, width, (it.paddingTop + it.layout.height + it.paddingBottom) * rowFraction)
                canvas.translate(0f, -(it.paddingTop + it.layout.height + it.paddingBottom) * (1f - rowFraction))
            }
            var spanEnd = -1
            var spanStartGradient = -1
            var realGradientStart = -1
            var realGradientEnd = -1
            var wordIdx: Int? = null
            var gradientProgress = Float.NEGATIVE_INFINITY
            val firstTs = if (it.isAttributionFooter) 0uL else it.line?.start ?: ULong.MIN_VALUE
            var lastTs = if (it.isAttributionFooter) 0uL else
                min(it.line?.end ?: Int.MAX_VALUE.toULong(), Int.MAX_VALUE.toULong())
            var endIsImplicit = !it.isAttributionFooter && it.line?.endIsImplicit != false
            if (Flags.IGNORE_SMALL_ENDTIME_GAPS && it.speaker?.isBackground != true &&
                it.line?.start != null && (!it.line.isTranslated
                        && it.theWords == null || it.line.isTranslated && spForRender!!.second
                    .subList(0, i).find { l -> l.line?.start == it.line.start }?.theWords == null)) {
                val j = spForRender!!.second.subList(i, spForRender!!.second.size).find { l ->
                    (l.line?.start ?: Int.MAX_VALUE.toULong()) > it.line.start }?.line?.start
                val nextStartTime = min(j ?: Int.MAX_VALUE.toULong(), Int.MAX_VALUE.toULong())
                if (j != null && abs(nextStartTime.toLong() - lastTs.toLong()) < lyricAnimTime) {
                    lastTs = nextStartTime - 1uL
                    endIsImplicit = true
                }
            }
            if (Flags.NO_ANIM_GRADIENT_LAST_FRAME_MONKEY_FIX && lyricAnimTime == 0f && it.theWords != null) {
                lastTs += 17.toUInt() // the assumption is that display is at least 60hz
            }
            val timeOffsetForUse = min(
                scaleInAnimTime, min(
                    lerp(
                        firstTs.toFloat(), lastTs.toFloat(),
                        0.5f
                    ) - firstTs.toFloat(),
                    max(firstTs.toFloat(), scaleInAnimTime)
                )
            )
            val fadeInStart = max(firstTs.toLong() - timeOffsetForUse.toLong(), 0L).toULong()
            val fadeInEnd = firstTs + timeOffsetForUse.toULong()
            // If end is implicit, it's the start point of next line, so animate smoothly.
            val fadeOutStart = if (!endIsImplicit) lastTs
            else lastTs - timeOffsetForUse.toULong()
            val fadeOutEnd = if (!endIsImplicit)
                lastTs + (timeOffsetForUse * 2).toULong()
            else lastTs + timeOffsetForUse.toULong()
            val highlightReal = !it.isAttributionFooter && posForRender in fadeInStart..fadeOutEnd
            val override = stateOverrides[i]
            val overridePos = override?.let {
                if (it >= 0f)
                    it.toULong() + posForRender - stateTime
                else (-it).toULong() // negative signals freeze
            } ?: posForRender
            val highlight = !it.isAttributionFooter && overridePos in fadeInStart..fadeOutEnd
            if (override != null) {
                val animPosReal = if (!highlightReal) 0f else if (posForRender >= fadeInEnd)
                    min(
                        1f, 1f - lerpInv(
                            fadeOutStart.toFloat(),
                            fadeOutEnd.toFloat(), posForRender.toFloat()
                        )
                    )
                else lerpInv(
                    fadeInStart.toFloat(),
                    fadeInEnd.toFloat(), posForRender.toFloat()
                )
                val animPos = if (!highlight) 0f else if (overridePos >= fadeInEnd) min(
                    1f,
                    1f - lerpInv(
                        fadeOutStart.toFloat(), fadeOutEnd.toFloat(),
                        overridePos.toFloat()
                    )
                ) else lerpInv(
                    fadeInStart.toFloat(),
                    fadeInEnd.toFloat(), overridePos.toFloat()
                )
                if (timeOffsetForUse == 0f || if (overridePos >= fadeInEnd) animPos <= animPosReal
                    else animPos >= animPosReal)
                    stateOverrides.remove(i)
            }
            val scaleInProgress = if (it.line == null) 1f else lerpInv(
                fadeInStart.toFloat(), fadeInEnd.toFloat(),
                overridePos.toFloat()
            )
            val scaleOutProgress = if (it.line == null) 1f else lerpInv(
                fadeOutStart.toFloat(),
                fadeOutEnd.toFloat(),
                overridePos.toFloat()
            )
            val hlScaleFactor = if (it.line == null) 1f else {
                // lerp() argument order is swapped because we divide by this factor
                if (scaleOutProgress in 0f..1f && timeOffsetForUse > 0f)
                    lerp(
                        smallSizeFactor,
                        1f,
                        scaleColorInterpolator.getInterpolation(scaleOutProgress)
                    )
                else if (scaleInProgress in 0f..1f && timeOffsetForUse > 0f)
                    lerp(
                        1f,
                        smallSizeFactor,
                        scaleColorInterpolator.getInterpolation(scaleInProgress)
                    )
                else if (highlight)
                    smallSizeFactor
                else 1f
            }
            val node = if (hasRenderNodes()) {
                val node = cachedNodes!![i]
                if (node == null) {
                    val newNode = RenderNode("NewLyricsView_$i")
                    cachedNodes[i] = newNode
                    newNode
                } else node
            } else null
            var hasValidCachedNode = if (hasRenderNodes()) node!!.hasDisplayList() else false
            val isRtl = it.layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
            val alignmentNormal = if (isRtl) it.layout.alignment == Layout.Alignment.ALIGN_OPPOSITE
            else it.layout.alignment == Layout.Alignment.ALIGN_NORMAL
            if (((scaleInProgress >= -.1f && scaleInProgress <= 1f) ||
                (scaleOutProgress >= -.1f && scaleOutProgress <= 1f)) &&
                timeOffsetForUse > 0f && it.line != null && isPlaying
            )
                animating = true
            if (i == scrollTargetIndex) scrollTargetPosition = heightSoFar.toInt()
            heightSoFar += it.paddingTop.toFloat()
            val culledDown = heightSoFar > scrollY + height
            var delayedScrollOffset = 0
            // TODO: is this +1 find check valid for tl+bg? the idea is that tl lines stick to
            //  their main line and are animated exactly the same.
            if (delayedScrollAnimation != null && delayedScrollAnimation!!.second.first < i &&
                !delayedScrollDoneForFrame && spForRender!!.second.subList(delayedScrollAnimation!!
                    .second.first + 1, i + 1).find { it.line?.isTranslated != true } != null) {
                val ii = spForRender!!.second.subList(delayedScrollAnimation!!.second.first + 1,
                    i + 1).sumOf { if (it.line?.isTranslated == true) 0 else 1 }
                val duration = lyricAnimTime * 0.278
                val durationReturn = lyricAnimTime * 0.722
                val durationStep = lyricAnimTime * 0.1
                val start = delayedScrollAnimation!!.first.toDouble()
                val end = start + duration + durationReturn + ii * durationStep
                if (end > cat) { // animation is still ongoing
                    if (!culledDown) {
                        val middle = start + duration
                        delayedScrollOffset += if (middle <= cat) {
                            val progress = lerpInv(middle, end, cat).toFloat()
                            val p = delayedOutInterpolator.getInterpolation(progress)
                            lerp(depth, 0f, p)
                        } else {
                            val progress = lerpInv(start, middle, cat).toFloat()
                            val p = delayedInInterpolator.getInterpolation(progress)
                            lerp(0f, depth, p)
                        }.toInt()
                        animating = true
                    } else {
                        delayedScrollDoneForFrame = true
                    }
                } else if (culledDown) {
                    delayedScrollAnimation = null
                }
            }
            canvas.translate(0f, it.paddingTop.toFloat() + delayedScrollOffset -
                    (it.layout.height.toFloat() / hlScaleFactor - it.layout.height.toFloat()) / 2)
            val culled = culledDown || scrollY - paddingTop > heightSoFar +
                    it.layout.height.toFloat() + it.paddingBottom
            if (!culled) {
                if (highlight) {
                    canvas.save()
                    canvas.scale(1f / hlScaleFactor, 1f / hlScaleFactor)
                    if (it.theWords != null) {
                        wordIdx = it.theWords.indexOfLast { it.timeRange.first <= posForRender }
                        if (wordIdx == -1) wordIdx = null
                        if (wordIdx != null) {
                            val word = it.theWords[wordIdx]
                            spanEnd = word.charRange.last + 1 // get exclusive end
                            val gradientEndTime = min(
                                fadeOutStart.toFloat(),
                                word.timeRange.last.toFloat()
                            )
                            val gradientStartTime = min(
                                max(
                                    word.timeRange.first.toFloat(),
                                    firstTs.toFloat()
                                ), gradientEndTime - 1f
                            )
                            gradientProgress = lerpInv(
                                gradientStartTime, gradientEndTime,
                                posForRender.toFloat()
                            )
                            val wordEndLine = it.layout.getLineForOffset(word.charRange.last)
                            val lastCharOnEndLineExcl = it.layout.getLineEnd(wordEndLine)
                            val lastWordOnLine = spanEnd >= lastCharOnEndLineExcl
                            // if we're here, this is the last active word on this line, but it may
                            // not be the last word on this line. if it isn't, keep rendering the
                            // gradient at 100% even after it ended (but only until next word is
                            // the last active word) to avoid kerning jumps due to switching to
                            // color span for parts of a line that should be in the same span.
                            if (gradientProgress >= 0f && (gradientProgress <= 1f || !lastWordOnLine)) {
                                spanStartGradient = word.charRange.first
                                // be greedy and eat as much as the line as can be eaten (text that is
                                // same line + is in same text direction). improves font rendering for
                                // Japanese if font rendering processes whole text in one pass
                                val wordStartLine = it.layout.getLineForOffset(word.charRange.first)
                                val firstCharOnStartLine = it.layout.getLineStart(wordStartLine)
                                realGradientStart = it.theWords.lastOrNull {
                                    it.charRange.last >= firstCharOnStartLine && it.charRange.last <
                                            word.charRange.first && it.isRtl != word.isRtl
                                }?.charRange?.last?.plus(1) ?: firstCharOnStartLine
                                realGradientEnd = it.theWords.firstOrNull {
                                    it.charRange.first > word.charRange.last && it.charRange.first <
                                            lastCharOnEndLineExcl && it.isRtl != word.isRtl
                                }?.charRange?.first ?: lastCharOnEndLineExcl
                            }
                        }
                    } else {
                        spanEnd = it.text.length
                    }
                }
                if (!alignmentNormal) {
                    if (!highlight)
                        canvas.save()
                    if (it.layout.alignment != Layout.Alignment.ALIGN_CENTER)
                        canvas.translate(width * (1 - smallSizeFactor / hlScaleFactor), 0f)
                    else // Layout.Alignment.ALIGN_CENTER
                        canvas.translate(width * ((1 - smallSizeFactor / hlScaleFactor) / 2), 0f)
                }
                if (gradientProgress >= -.1f && gradientProgress <= 1f && isPlaying)
                    animating = true
            }
            val spanEndWithoutGradient = if (realGradientStart == -1) spanEnd else realGradientStart
            val inColorAnim = ((scaleInProgress in 0f..1f && gradientProgress ==
                    Float.NEGATIVE_INFINITY) || scaleOutProgress in 0f..1f) &&
                    timeOffsetForUse > 0f
            var colorSpan = it.text.getSpans<MyForegroundColorSpan>().firstOrNull()
            val cachedEnd = colorSpan?.let { j -> it.text.getSpanEnd(j) } ?: -1
            val wordActiveSpanForLine = if (it.line?.isTranslated == true)
                wordActiveTlSpan else wordActiveSpan
            val col = if (!culled) {
                val highlightColorForLine = if (it.line?.isTranslated == true)
                    highlightTlTextColor else activeLyricsColor
                if (inColorAnim) ColorUtils.blendARGB(
                    if (scaleOutProgress in 0f..1f) highlightColorForLine else
                        defaultTextColor,
                    if (scaleInProgress in 0f..1f && gradientProgress == Float
                            .NEGATIVE_INFINITY
                    ) highlightColorForLine
                    else defaultTextColor,
                    scaleColorInterpolator.getInterpolation(
                        if (scaleOutProgress in 0f..1f
                        ) scaleOutProgress else scaleInProgress
                    )
                ) else Color.GREEN
            } else Color.RED
            if (cachedEnd != spanEndWithoutGradient || inColorAnim != (colorSpan != wordActiveSpanForLine)) {
                if (cachedEnd != -1) {
                    it.text.removeSpan(colorSpan!!)
                    if (colorSpan != wordActiveSpanForLine && (!inColorAnim || spanEndWithoutGradient == -1)) {
                        if (colorSpanPool.size < 10)
                            colorSpanPool.add(colorSpan)
                        colorSpan = null
                    } else if (inColorAnim && colorSpan == wordActiveSpanForLine)
                        colorSpan = null
                    hasValidCachedNode = false
                }
                if (spanEndWithoutGradient != -1) {
                    if (inColorAnim && colorSpan == null)
                        colorSpan = colorSpanPool.removeFirstOrNull()
                            ?: @SuppressLint("DrawAllocation") MyForegroundColorSpan(col)
                    else if (!inColorAnim)
                        colorSpan = wordActiveSpanForLine
                    it.text.setSpan(
                        colorSpan, 0, spanEndWithoutGradient,
                        Spanned.SPAN_INCLUSIVE_INCLUSIVE
                    )
                    hasValidCachedNode = false
                }
            }
            if (inColorAnim && spanEndWithoutGradient != -1) {
                if (colorSpan!! == wordActiveSpanForLine)
                    throw IllegalStateException("colorSpan == wordActiveSpan")
                if (colorSpan.color != col) {
                    colorSpan.color = col
                    hasValidCachedNode = false
                }
            }
            var gradientSpan = it.text.getSpans<MyGradientSpan>().firstOrNull()
            val gradientSpanStart = gradientSpan?.let { j -> it.text.getSpanStart(j) } ?: -1
            val gradientSpanEnd = gradientSpan?.let { j -> it.text.getSpanEnd(j) } ?: -1
            if (gradientSpanStart != realGradientStart || gradientSpanEnd != realGradientEnd) {
                val gradientSpanPoolForLine = if (it.line?.isTranslated == true)
                    gradientTlSpanPool else gradientSpanPool
                if (gradientSpanStart != -1) {
                    it.text.removeSpan(gradientSpan!!)
                    if (realGradientStart == -1) {
                        if (gradientSpanPoolForLine.size < 10)
                            gradientSpanPoolForLine.add(gradientSpan)
                        gradientSpan = null
                    }
                    hasValidCachedNode = false
                }
                if (realGradientStart != -1) {
                    if (gradientSpan == null)
                        gradientSpan = gradientSpanPoolForLine.removeFirstOrNull()
                            ?: if (it.line?.isTranslated == true) makeGradientTlSpan()
                            else makeGradientSpan()
                    it.text.setSpan(
                        gradientSpan, realGradientStart, realGradientEnd,
                        Spanned.SPAN_INCLUSIVE_INCLUSIVE
                    )
                    hasValidCachedNode = false
                }
            }
            if (!culled) {
                if (gradientSpan != null) {
                    gradientSpan.runCount = 0
                    gradientSpan.lastLineCount = -1
                    gradientSpan.lineOffsets = it.words!![wordIdx!!]
                    gradientSpan.totalCharsForProgress = spanEnd - spanStartGradient
                    // We get called once per run + one additional time per run if run direction isn't
                    // same as paragraph direction.
                    gradientSpan.runToLineMappings = it.rlm!!
                    val newProgress = gradientProgress.coerceAtMost(1f)
                    if (gradientSpan.progress != newProgress) {
                        gradientSpan.progress = newProgress
                        hasValidCachedNode = false
                    }
                }
                val backgroundSave = if (it.speaker?.isBackground == true) {
                    canvas.saveLayerAlpha(0f, 0f, width, it.layout.height.toFloat(), (192 * rowFraction).roundToInt())
                } else null
                val activeWord = wordIdx?.let { index -> it.longWords.getOrNull(index) }
                val wordScale = activeWordScale(activeWord?.word, posForRender, lyricAnimTime > 0f)
                if (activeWord != null && isPlaying &&
                    posForRender >= activeWord.word.begin && posForRender <= activeWord.word.end
                ) animating = true
                if (wordScale > 1f && activeWord != null) {
                    node?.discardDisplayList()
                    canvas.save()
                    @Suppress("DEPRECATION")
                    canvas.clipPath(activeWord.path, Region.Op.DIFFERENCE)
                    it.layout.draw(canvas)
                    canvas.restore()
                    canvas.save()
                    canvas.scale(wordScale, wordScale, activeWord.bounds.centerX(), activeWord.bounds.centerY())
                    canvas.clipPath(activeWord.path)
                    it.layout.draw(canvas)
                    canvas.restore()
                } else if (hasRenderNodes()) {
                    if (!hasValidCachedNode) {
                        node!!.setPosition(0, 0, width.toInt(),
                            it.layout.height)
                        val canvas = node.beginRecording()
                        it.layout.draw(canvas)
                        node.endRecording()
                    }
                    canvas.drawRenderNode(node!!)
                } else {
                    it.layout.draw(canvas)
                }
                if (backgroundSave != null) canvas.restoreToCount(backgroundSave)
                if (highlight || !alignmentNormal)
                    canvas.restore()
            }

            canvas.translate(0f, (it.layout.height.toFloat()) / hlScaleFactor -
                    (it.layout.height.toFloat() / hlScaleFactor - it.layout.height.toFloat()) / 2
                    + it.paddingBottom.toFloat() - delayedScrollOffset)
            heightSoFar += it.layout.height + it.paddingBottom
            if (rowFraction < 1f) {
                canvas.restore()
                val visibleHeight = (it.paddingTop + it.layout.height + it.paddingBottom) * rowFraction
                canvas.translate(0f, visibleHeight)
                heightSoFar -= (it.paddingTop + it.layout.height + it.paddingBottom) - visibleHeight
            }
        }
        //heightSoFar += globalPaddingBottom
        canvas.restore()
        if (animating)
            postInvalidateOnAnimation()
        if (isUserInteractingWithScrollView) {
            handler.removeCallbacks(invalidateCallback)
            handler.postDelayed(invalidateCallback, 5000)
            isCallbackQueued = true
            if (spForRender!!.first[3] == 1)
                currentScrollTarget = null
        } else if (!isCallbackQueued) {
            val scrollTarget = max(0, (scrollTargetPosition ?: globalPaddingTop) - globalPaddingTop)
            val running = currentSmoothScroll
            if (running != null && running.targetIndex == scrollTargetIndex) {
                running.target = scrollTarget.toFloat()
                currentScrollTarget = scrollTarget
            } else if (scrollTarget != currentScrollTarget || running != null) {
                currentScrollTarget = scrollTarget
                if (lyricAnimTime == 0f) {
                    currentSmoothScroll = null
                    scrollTo(0, scrollTarget)
                } else {
                    currentSmoothScroll = SmoothScroll(
                        AnimationUtils.currentAnimationTimeMillis().toDouble(),
                        lyricAnimTime.toDouble(), scrollY.toFloat(), scrollTarget.toFloat(),
                        scrollTargetIndex
                    )
                    runAnimatedScroll(false)
                    if (scrollY < scrollTarget) {
                        delayedScrollAnimation = if (scrollTargetIndex != null) AnimationUtils
                            .currentAnimationTimeMillis() to (scrollTargetIndex to scrollY)
                        else null
                    }
                }
            }
        }
    }

    override fun onTouchEventForChild(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    override fun onMeasureForChild(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val myWidth = getDefaultSize(minimumWidth, widthMeasureSpec)
        if (spForMeasure == null || spForMeasure!!.first[0] != myWidth)
            spForMeasure = buildSpForMeasure(lyrics, myWidth)
        setChildMeasuredDimension(
            myWidth,
            getDefaultSize(spForMeasure!!.first[1], heightMeasureSpec)
        )
    }

    override fun onLayoutForChild(left: Int, top: Int, right: Int, bottom: Int) {
        if (spForMeasure == null || spForMeasure!!.first[0] != right - left
            || spForMeasure!!.first[1] != bottom - top
        )
            spForMeasure = buildSpForMeasure(lyrics, right - left)
        spForRender = spForMeasure!!
        invalidateDeeply()
    }

    private fun invalidateDeeply() {
        if (hasRenderNodes()) {
            cachedNodes!!.forEach { _, it ->
                it.discardDisplayList()
            }
            cachedNodes.clear()
        }
        invalidate()
    }

    fun buildSpForMeasure(lyrics: SemanticLyrics?, width: Int): Pair<IntArray, List<SbItem>> {
        val lines =
            lyrics?.unsyncedText ?: listOf(context.getString(R.string.no_lyric_found) to null)
        val syncedLines = (lyrics as? SemanticLyrics.SyncedLyrics?)?.text
        var lastNonTranslated: SemanticLyrics.LyricLine? = null
        val spLines = lines.mapIndexed { i, it ->
            val syncedLine = syncedLines?.get(i)
            if (syncedLine?.isTranslated != true)
                lastNonTranslated = syncedLine
            val words =
                syncedLine?.words ?: if (prefs.getBooleanStrict("translation_auto_word", false) &&
                    syncedLine?.isTranslated == true && lastNonTranslated?.words != null
                )
                    listOf(
                        SemanticLyrics.Word(
                            lastNonTranslated.timeRange, 0..<syncedLine.text.length,
                            findBidirectionalBarriers(syncedLine.text).firstOrNull()?.second == true
                        )
                    ) else null
            val sb = SpannableStringBuilder(it.first)
            val speaker = syncedLine?.speaker ?: it.second
            val paragraphRtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(sb, 0, sb.length)
            val alignRight = (speaker?.isVoice2 == true) != (layoutDirection == View.LAYOUT_DIRECTION_RTL)
            val align =
                if (prefs.getBooleanStrict("lyric_center", false) || speaker?.isGroup == true)
                    Layout.Alignment.ALIGN_CENTER
                else if (alignRight != paragraphRtl)
                    Layout.Alignment.ALIGN_OPPOSITE
                else Layout.Alignment.ALIGN_NORMAL
            val tl = syncedLine?.isTranslated == true
            val bg = speaker?.isBackground == true
            // TODO: width limiting to 85% if there is >1 singer
            //val widthLimit = speaker?.isWidthLimited == true
            val paddingTop = if (tl) paddingVerticalTl else paddingVerticalDefault
            val paddingBottom = if (i + 1 < (syncedLines?.size ?: -1) &&
                syncedLines?.get(i + 1)?.isTranslated == true
            ) paddingVerticalTl else paddingVerticalDefault
            val layout = StaticLayoutBuilderCompat.obtain(
                sb, when {
                    tl && bg -> translationBackgroundTextPaint
                    tl || bg -> translationTextPaint
                    else -> defaultTextPaint
                }, (width * smallSizeFactor).toInt() - globalPaddingHorizontal.toInt() * 2
            ).setAlignment(align).build()
            val layoutRtl = layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
            val alignmentNormal = if (layoutRtl) align == Layout.Alignment.ALIGN_OPPOSITE
            else align == Layout.Alignment.ALIGN_NORMAL
            var l: StaticLayout? = null
            val lineOffsets = words?.map {
                val ia = mutableListOf<Int>()
                val firstLine = layout.getLineForOffset(it.charRange.first)
                val lastLine = layout.getLineForOffset(it.charRange.last + 1)
                for (line in firstLine..lastLine) {
                    val lineStart = layout.getLineStart(line)
                    var lineEnd = layout.getLineEnd(line)
                    while (lineStart + 1 < lineEnd && (layout.text[lineEnd - 1] == '\n' || layout.text[lineEnd - 1] == '\r'))
                        lineEnd--
                    val firstInLine = max(it.charRange.first, lineStart)
                    val lastInLineExcl = min(it.charRange.last + 1, lineEnd)
                    val horizontalStart = if (layoutRtl == it.isRtl)
                        layout.getPrimaryHorizontal(firstInLine)
                    else layout.getSecondaryHorizontal(firstInLine)
                    // Recycle the layout if we have multiple words in one line.
                    if (l == null || l.getLineStart(0) != lineStart
                        || (l.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT) != it.isRtl
                    ) {
                        // Use StaticLayout instead of Paint.measureText() for V+ useBoundsForWidth
                        // TODO is this working since moving to getPrimaryHorizontal() again?
                        /*
                         * TODO replace this code with something that does not need a new layout whenever possible.
                         * some ideas:
                         * https://developer.android.com/reference/android/text/Layout#fillCharacterBounds(int,%20int,%20float[],%20int) (API >=34)
                         * https://developer.android.com/reference/android/text/Layout#getSelectionPath(int,%20int,%20android.graphics.Path) (API >=26 or >=34 for path parsing)
                         * https://developer.android.com/reference/android/graphics/Paint#getRunCharacterAdvance(char[],%20int,%20int,%20int,%20int,%20boolean,%20int,%20float[],%20int) (API >=34)
                         * https://developer.android.com/reference/android/graphics/Paint#getRunAdvance(char[],%20int,%20int,%20int,%20int,%20boolean,%20int) (API >=23)
                         */
                        l = StaticLayoutBuilderCompat
                            .obtain(layout.text, layout.paint, Int.MAX_VALUE)
                            .setAlignment(
                                if (it.isRtl) Layout.Alignment.ALIGN_OPPOSITE
                                else Layout.Alignment.ALIGN_NORMAL
                            )
                            .setIsRtl(it.isRtl)
                            .setStart(lineStart)
                            .setEnd(lineEnd)
                            .build()
                    }
                    val w = (l.getPrimaryHorizontal(if (it.isRtl) firstInLine else lastInLineExcl)
                            - l.getPrimaryHorizontal(if (it.isRtl) lastInLineExcl else firstInLine)) +
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                                // just add a few pixels on top if RTL as approximation :D
                                if (it.isRtl) 5 else 0
                            } else 0
                    val horizontalEnd = horizontalStart + w * if (it.isRtl) -1 else 1
                    val horizontalLeft = min(horizontalStart, horizontalEnd)
                    val horizontalRight = max(horizontalStart, horizontalEnd)
                    ia.add(horizontalLeft.toInt()) // offset from left to start of word
                    ia.add((horizontalRight - horizontalLeft).roundToInt()) // width of text in this line
                    ia.add(firstInLine - it.charRange.first)
                    ia.add(lastInLineExcl - it.charRange.first)
                    ia.add(if (it.isRtl) -1 else 1)
                }
                return@map ia
            }
            SbItem(
                layout, sb, paddingTop.dpToPx(context).toInt(),
                paddingBottom.dpToPx(context).toInt(),
                words, lineOffsets, lineOffsets?.let { _ ->
                    (0..<layout.lineCount).map { line ->
                        findBidirectionalBarriers(
                            layout.text.subSequence(
                                layout.getLineStart(line), layout.getLineEnd(line)
                            )
                        ).flatMap {
                            if (it.second == alignmentNormal)
                                listOf(line, line)
                            else
                                listOf(line)
                        }
                    }.flatten()
                }, speaker, syncedLine,
                longWords = prepareLongLyricWords(words).map { word ->
                    if (word == null || word.charRange.first < 0 ||
                        word.charRange.last < word.charRange.first || word.charRange.last >= sb.length
                    ) null else {
                        val path = Path()
                        layout.getSelectionPath(word.charRange.first, word.charRange.last + 1, path)
                        val bounds = RectF()
                        path.computeBounds(bounds, true)
                        PreparedWordDrawing(word, path, bounds)
                    }
                }
            )
        }.toMutableList()
        buildAttributionItem(width)?.let(spLines::add)
        val viewportHeight = measuredHeight - paddingBottom - paddingTop
        val heights = spLines.map { it.layout.height + it.paddingTop + it.paddingBottom }
        val globalPaddingTop = if (lyrics is SemanticLyrics.SyncedLyrics) viewportHeight / 6 else
            context.resources.getDimensionPixelSize(R.dimen.lyric_top_padding)
        val lastIdx = spLines.indexOfLast { !it.isAttributionFooter && it.speaker?.isBackground != true &&
                it.line?.isTranslated != true }.takeIf { it != -1 }
        val globalPaddingBottom = if (lyrics is SemanticLyrics.SyncedLyrics) max(0,
            (viewportHeight * (5f / 6f)).toInt() -
                    (lastIdx?.let { heights[it] } ?: 0))
        else if (lyrics != null) context.resources.getDimensionPixelSize(R.dimen.lyric_bottom_padding) else 0
        return Pair(
            intArrayOf(
                width,
                heights.sum() + globalPaddingTop + globalPaddingBottom,
                globalPaddingTop,
                if (lyrics is SemanticLyrics.SyncedLyrics) 1 else 0,
                globalPaddingBottom
            ), spLines
        )
    }

    private fun buildAttributionItem(width: Int): SbItem? {
        val attribution = lyricsAttribution ?: return null
        val text = buildLyricsAttribution(context, attribution)
        val paint = TextPaint(defaultTextPaint).apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                16f,
                context.resources.displayMetrics
            )
            color = LYRICS_ATTRIBUTION_COLOR
        }
        val layout = StaticLayoutBuilderCompat.obtain(
            text,
            paint,
            (width * smallSizeFactor).toInt() - globalPaddingHorizontal.toInt() * 2
        ).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        return SbItem(
            layout = layout,
            text = text,
            paddingTop = 32f.dpToPx(context).toInt(),
            paddingBottom = 64f.dpToPx(context).toInt(),
            theWords = null,
            words = null,
            rlm = null,
            speaker = null,
            line = null,
            isAttributionFooter = true
        )
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (spForRender == null) {
            requestLayout()
            return true
        }
        val y = e.y
        val x = e.x - globalPaddingHorizontal
        var heightSoFar = spForRender!!.first[2]
        spForRender!!.second.forEachIndexed { index, item ->
            val fraction = backgroundRowFraction(index, item)
            if (fraction <= 0f) return@forEachIndexed
            val rowTop = heightSoFar + item.paddingTop -
                (item.paddingTop + item.layout.height + item.paddingBottom) * (1f - fraction)
            val rowBottom = rowTop + item.layout.height
            if (item.isAttributionFooter && y in rowTop.toFloat()..rowBottom.toFloat()) {
                val lineIndex = item.layout.getLineForVertical((y - rowTop).toInt())
                if (x >= item.layout.getLineLeft(lineIndex) && x <= item.layout.getLineRight(lineIndex)) {
                    val offset = item.layout.getOffsetForHorizontal(lineIndex, x)
                    item.text.getSpans(offset, offset, ClickableSpan::class.java)
                        .firstOrNull { offset >= item.text.getSpanStart(it) && offset < item.text.getSpanEnd(it) }
                        ?.onClick(this)
                }
            }
            heightSoFar += ((item.paddingTop + item.layout.height + item.paddingBottom) * fraction).roundToInt()
        }
        return true
    }

    fun handleSeek(from: ULong, to: ULong) {
        spForRender?.second?.forEachIndexed { i, it ->
            if (it.isAttributionFooter) return@forEachIndexed
            val firstTs = it.line?.start ?: ULong.MIN_VALUE
            var lastTs = min(it.line?.end ?: Int.MAX_VALUE.toULong(), Int.MAX_VALUE.toULong())
            var endIsImplicit = it.line?.endIsImplicit != false
            if (Flags.IGNORE_SMALL_ENDTIME_GAPS && it.speaker?.isBackground != true &&
                it.line?.start != null && (!it.line.isTranslated
                        && it.theWords == null || it.line.isTranslated && spForRender!!.second
                    .subList(0, i).find { l -> l.line?.start == it.line.start }?.theWords == null)) {
                val j = spForRender!!.second.subList(i, spForRender!!.second.size).find { l ->
                    (l.line?.start ?: Int.MAX_VALUE.toULong()) > it.line.start }?.line?.start
                val nextStartTime = min(j ?: Int.MAX_VALUE.toULong(), Int.MAX_VALUE.toULong())
                if (j != null && abs(nextStartTime.toLong() - lastTs.toLong()) < lyricAnimTime) {
                    lastTs = nextStartTime
                    endIsImplicit = true
                }
            }
            if (Flags.NO_ANIM_GRADIENT_LAST_FRAME_MONKEY_FIX && lyricAnimTime == 0f && it.theWords != null) {
                lastTs += 17.toUInt() // the assumption is that display is at least 60hz
            }
            val timeOffsetForUse = min(
                scaleInAnimTime, min(
                    lerp(
                        firstTs.toFloat(), lastTs.toFloat(),
                        0.5f
                    ) - firstTs.toFloat(),
                    max(firstTs.toFloat(), scaleInAnimTime)
                )
            )
            val fadeInStart = max(firstTs.toLong() - timeOffsetForUse.toLong(), 0L).toULong()
            val fadeInEnd = firstTs + timeOffsetForUse.toULong()
            // If end is implicit, it's the start point of next line, so animate smoothly.
            val fadeOutStart = if (!endIsImplicit) lastTs
            else lastTs - timeOffsetForUse.toULong()
            val fadeOutEnd = if (!endIsImplicit)
                lastTs + (timeOffsetForUse * 2).toULong()
            else lastTs + timeOffsetForUse.toULong()
            val override = stateOverrides[i]
            val overridePos = override?.let {
                // if there's an old override we'll continue there
                if (it >= 0f)
                    it.toULong() + from - stateTime
                else (-it).toULong() // negative signals freeze
            } ?: from
            val highlight = overridePos in fadeInStart..fadeOutEnd
            val animPosNow = if (!highlight) 0f else if (overridePos >= fadeInEnd)
                min(1f, 1f - lerpInv(fadeOutStart.toFloat(),
                    fadeOutEnd.toFloat(), overridePos.toFloat()))
            else lerpInv(fadeInStart.toFloat(),
                fadeInEnd.toFloat(), overridePos.toFloat())
            val highlightAfterSeek = to in fadeInStart..fadeOutEnd
            val animPosAfterSeek = if (!highlightAfterSeek) 0f else if (to >=
                fadeInEnd) min(1f, 1f - lerpInv(fadeOutStart.toFloat(),
                fadeOutEnd.toFloat(), to.toFloat()))
            else lerpInv(fadeInStart.toFloat(),
                fadeInEnd.toFloat(), to.toFloat())
            if (animPosNow != animPosAfterSeek && it.theWords == null)
                stateOverrides[i] =
                    // Now we have to decide what behavior towards infinity we wish to have...
                    when {
                        // If we are fading out or fully faded out at target, skip to fade out
                        // at current animation point
                        to !in fadeInStart..<fadeOutStart ->
                            lerp(fadeOutStart.toFloat(), fadeOutEnd.toFloat(),
                                1f - animPosNow)
                        // If we're fading in at target and are already fully faded in here,
                        // stay fully faded in and wait for target to finish fading in too.
                        overridePos in fadeInEnd..<fadeOutStart ->
                            -overridePos.toFloat() // negative signals freeze
                        else -> lerp(fadeInStart.toFloat(), fadeInEnd.toFloat(),
                            animPosNow)
                    }
            else if (override != null)
                stateOverrides.remove(i)
        }
        stateTime = to
    }

    private fun getScrollProgressAt(time: Double): Int {
        val scroll = currentSmoothScroll!!
        val progress = lerpInv(scroll.startTime, scroll.startTime + scroll.duration, time).toFloat()
        val interpolatedProgress = scrollInterpolator.getInterpolation(progress.coerceIn(0f, 1f))
        return lerp(scroll.start, scroll.target, interpolatedProgress).toInt()
    }

    override fun computeScrollInner(): Long {
        val cat = AnimationUtils.currentAnimationTimeMillis().toDouble()
        val q = getScrollProgressAt(cat)
        val q1 = getScrollProgressAt(cat - 16.0)
        val velocity = (1000f * (q - q1) / 16f)
        if (cat >= currentSmoothScroll!!.startTime + currentSmoothScroll!!.duration) {
            currentSmoothScroll = null
        }
        return q.toLong() or velocity.toBits().toLong().shl(32)
    }

    override fun shouldComputeScrollInner(): Boolean {
        return currentSmoothScroll != null
    }

    override fun abortAnimatedScroll() {
        currentSmoothScroll = null
        super.abortAnimatedScroll()
    }

    override fun startNestedScroll(axes: Int, type: Int): Boolean {
        currentSmoothScroll = null
        return super.startNestedScroll(axes, type)
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        return false
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        return false
    }

    override fun onDown(e: MotionEvent): Boolean {
        return true
    }

    override fun onShowPress(e: MotionEvent) {
        // do nothing
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        return false
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        return false // handled by parent
    }

    override fun onLongPress(e: MotionEvent) {
        // do nothing
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        return false // handled by parent
    }

    internal data class PreparedWordDrawing(val word: LongLyricWord, val path: Path, val bounds: RectF)

    data class SbItem internal constructor(
        val layout: StaticLayout, val text: SpannableStringBuilder,
        val paddingTop: Int, val paddingBottom: Int, val theWords: List<SemanticLyrics.Word>?,
        val words: List<List<Int>>?, val rlm: List<Int>?, val speaker: SpeakerEntity?,
        val line: SemanticLyrics.LyricLine?,
        val isAttributionFooter: Boolean = false,
        internal val longWords: List<PreparedWordDrawing?> = emptyList()
    )

}
