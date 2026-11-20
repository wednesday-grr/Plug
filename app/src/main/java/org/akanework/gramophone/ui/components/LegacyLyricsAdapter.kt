package org.akanework.gramophone.ui.components

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import androidx.core.animation.doOnEnd
import androidx.core.graphics.TypefaceCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.dpToPx
import org.akanework.gramophone.logic.getBooleanStrict
import org.akanework.gramophone.logic.getIntStrict
import org.akanework.gramophone.logic.ui.CustomSmoothScroller
import org.akanework.gramophone.logic.ui.MyRecyclerView
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SpeakerEntity
import org.akanework.gramophone.logic.utils.SpicyLyricsAttribution
import org.akanework.gramophone.logic.utils.SemanticLyrics.SyncedLyrics
import org.akanework.gramophone.ui.MainActivity
import kotlin.math.abs

class LegacyLyricsAdapter(
    private val context: Context,
) : MyRecyclerView.Adapter<LegacyLyricsAdapter.ViewHolder>() {
    companion object {
        const val LYRIC_REMOVE_HIGHLIGHT = 0
        const val LYRIC_SET_HIGHLIGHT = 1
        const val LYRIC_SCROLL_DURATION: Long = 650
    }

    private val activity
        get() = context as MainActivity
    var callback: NewLyricsView.Callbacks? = null
    private val speed
        get() = callback?.speed() ?: 1f
    private var recyclerView: MyRecyclerView? = null
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private var defaultTextColor = 0
    private var highlightTextColor = 0
    private var customHighlight = false
    private var playbackPosition = 0uL
    private data class PlaybackStyle(val visible: Boolean, val active: Boolean, val wordStart: Int, val scale: Int)
    private data class PlaybackPayload(val animateBackground: Boolean)
    private var playbackStyles = emptyMap<Int, PlaybackStyle>()
    private var lastPlaybackPosition: ULong? = null
    private var lastPlaybackUpdate = 0L
    private var backgroundGroups = LyricBackgroundGroups(emptyList())
    private val interpolator = PathInterpolator(0.4f, 0.2f, 0f, 1f)
    private val lyricList = mutableListOf<Lyric>()
    var currentFocusPos = -1
        private set
    private var currentTranslationPos = -1
    private var isLyricCentered = false
    private var forceNoAnimation = false
    private var lyricsAttribution: SpicyLyricsAttribution? = null
    private val sizeFactor = 1f
    private val defaultSizeFactor = .97f

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder =
        ViewHolder(
            LayoutInflater
                .from(parent.context)
                .inflate(R.layout.lyrics, parent, false),
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        throw IllegalStateException()
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val lyric = lyricList[position]
        val isHighlightPayload =
            payloads.isNotEmpty() && (payloads[0] == LYRIC_SET_HIGHLIGHT || payloads[0] == LYRIC_REMOVE_HIGHLIGHT)

        with(holder.lyricCard) {
            setOnClickListener(null)
            isClickable = false
            isFocusable = false
        }

        with(holder.lyricTextView) {
            visibility = if (lyric.content.isNotEmpty()) VISIBLE else GONE
            setTextIsSelectable(false)
            customSelectionActionModeCallback = null
            text = lyric.content
            gravity = when {
                lyric.isAttributionFooter -> Gravity.START
                isLyricCentered || lyric.speaker?.isGroup == true -> Gravity.CENTER
                lyric.speaker?.isVoice2 == true -> Gravity.END
                else -> Gravity.START
            }
            textAlignment = View.TEXT_ALIGNMENT_GRAVITY
            translationY = 0f

            val textSize = when {
                lyric.isAttributionFooter -> 16f
                lyric.isBackground -> 20f
                lyric.isTranslation -> 20f
                else -> 34.25f
            }
            val paddingTop = when {
                lyric.isAttributionFooter -> 32
                lyric.isTranslation -> 2
                else -> 18
            }
            val paddingBottom = when {
                lyric.isAttributionFooter -> 64
                position + 1 < lyricList.size && lyricList[position + 1].isTranslation -> 2
                else -> 18
            }

            this.typeface = ResourcesCompat.getFont(context, R.font.dongle_bold)
                ?: TypefaceCompat.create(context, null, 700, false)
            movementMethod = if (lyric.isAttributionFooter) LinkMovementMethod.getInstance() else null
            isClickable = lyric.isAttributionFooter || lyric.line != null
            isFocusable = lyric.isAttributionFooter || lyric.line != null
            isLongClickable = lyric.line != null
            setOnClickListener {
                lyric.line?.let { callback?.seekTo(it.start.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()) }
            }
            setOnLongClickListener {
                val textToCopy = lyric.line?.text?.takeIf(String::isNotBlank) ?: return@setOnLongClickListener false
                AlertDialog.Builder(context)
                    .setItems(arrayOf(
                        context.getString(R.string.lyrics_copy),
                        context.getString(R.string.lyrics_share)
                    )) { _, which ->
                        if (which == 0) {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("Lyrics", textToCopy))
                        } else {
                            context.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, textToCopy)
                                }, null
                            ))
                        }
                    }.show()
                true
            }

            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            setPadding(
                (12.5f).dpToPx(context).toInt(),
                paddingTop.dpToPx(context),
                (12.5f).dpToPx(context).toInt(),
                paddingBottom.dpToPx(context)
            )

            doOnLayout {
                pivotX = 0f
                pivotY = height / 2f
            }

            when {
                lyric.isAttributionFooter -> {
                    scaleText(1f)
                    setTextColor(LYRICS_ATTRIBUTION_COLOR)
                }
                isHighlightPayload -> {
                    val targetScale =
                        if (payloads[0] == LYRIC_SET_HIGHLIGHT) sizeFactor else defaultSizeFactor
                    val targetColor =
                        if (payloads[0] == LYRIC_SET_HIGHLIGHT)
                            highlightTextColor
                        else
                            defaultTextColor
                    animateText(targetScale, targetColor)
                }

                position == currentFocusPos || position == currentTranslationPos -> {
                    scaleText(sizeFactor)
                    setTextColor(highlightTextColor)
                }

                else -> {
                    scaleText(defaultSizeFactor)
                    setTextColor(defaultTextColor)
                }
            }
            val active = if (lyric.isBackground && lyric.line != null) {
                playbackPosition in lyric.line.timeRange
            } else position == currentFocusPos
            alpha = if (lyric.isBackground) 0.75f else 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
            if (active && !lyric.isTranslation) setTextColor(if (customHighlight) highlightTextColor else Color.WHITE)
            if (active) scaleText(1.03f)
        }
        val visible = lyric.line?.let { backgroundGroups.visible(it, playbackPosition) } ?: true
        val animate = payloads.filterIsInstance<PlaybackPayload>().any { it.animateBackground } &&
            holder.boundPosition == position && holder.lyricCard.isAttachedToWindow
        updateBackgroundVisibility(holder, position, visible, animate && lyric.isBackground)
        updateWordEffect(holder, lyric, playbackPosition)
    }

    private fun updateWordEffect(holder: ViewHolder, lyric: Lyric, position: ULong) {
        holder.wordFrame?.let { holder.lyricTextView.removeCallbacks(it) }
        holder.wordFrame = null
        val character = lyric.longCharacters.lastOrNull {
            position in it.timing.begin..it.timing.end
        }
        val speaker = lyric.speaker
        val scale = activeWordScale(
            character?.timing, position, !forceNoAnimation,
            prefs.getIntStrict("lyric_word_expansion_duration", DEFAULT_WORD_EXPANSION_DURATION_MS),
            lyricSpringSpec(
                prefs,
                if (speaker?.isBackground == true) "background"
                else if (speaker?.isVoice2 == true || speaker?.isGroup == true) "duet"
                else "primary"
            )
        )
        holder.lyricTextView.text = if (character != null && scale > 1f &&
            character.charRange.first >= 0 && character.charRange.last >= character.charRange.first &&
            character.charRange.last < lyric.content.length
        ) {
            SpannableString(lyric.content).apply {
                setSpan(RelativeSizeSpan(scale), character.charRange.first, character.charRange.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else lyric.content
        if (character != null && !forceNoAnimation && callback?.isPlaying() == true &&
            holder.lyricTextView.isAttachedToWindow
        ) {
            val boundPosition = holder.boundPosition
            val frame = Runnable {
                if (holder.boundPosition == boundPosition && holder.lyricTextView.isAttachedToWindow) {
                    updateWordEffect(holder, lyric, callback?.getCurrentPosition() ?: position)
                }
            }
            holder.wordFrame = frame
            holder.lyricTextView.postOnAnimation(frame)
        }
    }

    private fun updateBackgroundVisibility(holder: ViewHolder, position: Int, visible: Boolean, animate: Boolean) {
        val card = holder.lyricCard
        if (holder.boundPosition != position || !animate) {
            val oldAnimator = holder.backgroundAnimator
            holder.backgroundAnimator = null
            oldAnimator?.cancel()
            holder.boundPosition = position
            holder.backgroundTarget = visible
            card.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            card.alpha = 1f
            card.visibility = if (visible) VISIBLE else GONE
            return
        }
        if (holder.backgroundTarget == visible) return
        holder.backgroundTarget = visible
        val from = if (card.visibility == GONE) 0 else
            if (holder.backgroundAnimator != null) card.layoutParams.height.coerceAtLeast(0) else card.height
        val oldAnimator = holder.backgroundAnimator
        holder.backgroundAnimator = null
        oldAnimator?.cancel()
        card.visibility = VISIBLE
        val width = card.width.takeIf { it > 0 } ?: (card.parent as? View)?.width ?: 0
        if (width <= 0) {
            card.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            card.alpha = 1f
            card.visibility = if (visible) VISIBLE else GONE
            return
        }
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val fullHeight = card.measuredHeight
        val target = if (visible) fullHeight else 0
        val animator = ValueAnimator.ofInt(from, target)
        holder.backgroundAnimator = animator
        animator.duration = BACKGROUND_ROW_ANIMATION_MS
        animator.interpolator = interpolator
        animator.addUpdateListener {
            val height = it.animatedValue as Int
            card.layoutParams = card.layoutParams.apply { this.height = height }
            card.alpha = if (fullHeight > 0) (height.toFloat() / fullHeight).coerceIn(0f, 1f) else 1f
        }
        animator.doOnEnd {
            if (holder.backgroundAnimator === animator) {
                holder.backgroundAnimator = null
                card.layoutParams = card.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                card.alpha = 1f
                card.visibility = if (visible) VISIBLE else GONE
            }
        }
        animator.start()
    }

    private fun TextView.animateText(targetScale: Float, targetColor: Int) {
        val animator = ValueAnimator.ofFloat(scaleX, targetScale)
        animator.addUpdateListener { animation ->
            val animatedValue = animation.animatedValue as Float
            scaleX = animatedValue
            scaleY = animatedValue
        }
        animator.duration = (LYRIC_SCROLL_DURATION * speed).toLong()
        animator.interpolator = interpolator
        animator.start()

        val colorAnimator = ValueAnimator.ofArgb(textColors.defaultColor, targetColor)
        colorAnimator.addUpdateListener { animation ->
            val animatedValue = animation.animatedValue as Int
            setTextColor(animatedValue)
        }
        colorAnimator.duration = (LYRIC_SCROLL_DURATION * speed).toLong()
        colorAnimator.interpolator = interpolator
        colorAnimator.start()
    }

    private fun TextView.scaleText(scale: Float) {
        scaleX = scale
        scaleY = scale
    }

    override fun onAttachedToRecyclerView(recyclerView: MyRecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        updateLyricStatus()
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: MyRecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    fun updateLyricStatus() {
        isLyricCentered = prefs.getBooleanStrict("lyric_center", false)
        forceNoAnimation = prefs.getBooleanStrict("lyric_no_animation", false)
    }

    override fun getItemCount(): Int = lyricList.size

    class ViewHolder(
        view: View
    ) : RecyclerView.ViewHolder(view) {
        val lyricTextView: TextView = view.findViewById(R.id.lyric)
        val lyricCard: MaterialCardView = view.findViewById(R.id.cardview)
        internal var boundPosition = -1
        internal var backgroundTarget = true
        internal var backgroundAnimator: ValueAnimator? = null
        internal var wordFrame: Runnable? = null
    }

    override fun onViewRecycled(holder: ViewHolder) {
        val oldAnimator = holder.backgroundAnimator
        holder.backgroundAnimator = null
        oldAnimator?.cancel()
        holder.wordFrame?.let { holder.lyricTextView.removeCallbacks(it) }
        holder.wordFrame = null
        holder.boundPosition = -1
        holder.lyricCard.alpha = 1f
        holder.lyricCard.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        super.onViewRecycled(holder)
    }

    fun updateHighlight(position: Int) {
        val newFocus = position.takeIf { it in lyricList.indices }
        val newTranslation = newFocus?.let { focus ->
            lyricList.indices.firstOrNull {
                lyricList[it].isTranslation &&
                    lyricList[it].timeStamp == lyricList[focus].timeStamp
            }
        } ?: -1
        if (currentFocusPos == newFocus && currentTranslationPos == newTranslation) return
        val changedPositions = setOf(
            currentFocusPos,
            currentTranslationPos,
            newFocus ?: -1,
            newTranslation
        ).filter { it in lyricList.indices }.toMutableSet()
        currentFocusPos = newFocus ?: -1
        currentTranslationPos = newTranslation
        changedPositions.forEach { notifyItemChanged(it) }
    }

    private fun createSmoothScroller(noAnimation: Boolean): RecyclerView.SmoothScroller {
        return object : CustomSmoothScroller(context) {

            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                return super.calculateDtToFit(
                    viewStart,
                    viewEnd,
                    boxStart,
                    boxEnd,
                    snapPreference
                ) + 72.dpToPx(context)
            }

            override fun getVerticalSnapPreference(): Int {
                return SNAP_TO_START
            }

            override fun calculateTimeForDeceleration(dx: Int): Int {
                return (LYRIC_SCROLL_DURATION * speed).toInt()
            }

            override fun afterTargetFound() {
                if (targetPosition > 1) {
                    val firstVisibleItemPosition = targetPosition + 1
                    val lastVisibleItemPosition =
                        (layoutManager as LinearLayoutManager).findLastVisibleItemPosition() + 2
                    for (i in firstVisibleItemPosition..lastVisibleItemPosition) {
                        val view: View? = layoutManager!!.findViewByPosition(i)
                        if (view != null) {
                            if (i == targetPosition + 1 && lyricList[i].isTranslation) {
                                continue
                            }
                            if (!noAnimation) {
                                val ii = i - firstVisibleItemPosition -
                                        if (lyricList[i].isTranslation) 1 else 0
                                val depth = 15.dpToPx(context).toFloat()
                                val duration = (LYRIC_SCROLL_DURATION * 0.278 * speed).toLong()
                                val durationReturn =
                                    (LYRIC_SCROLL_DURATION * 0.722 * speed).toLong()
                                val durationStep = (LYRIC_SCROLL_DURATION * 0.1 * speed).toLong()
                                val animator = ObjectAnimator.ofFloat(
                                    view,
                                    "translationY",
                                    0f,
                                    depth,
                                )
                                animator.setDuration(duration)
                                animator.interpolator = PathInterpolator(0.96f, 0.43f, 0.72f, 1f)
                                animator.doOnEnd {
                                    val animator1 = ObjectAnimator.ofFloat(
                                        view,
                                        "translationY",
                                        depth,
                                        0f
                                    )
                                    animator1.setDuration(durationReturn + ii * durationStep)
                                    animator1.interpolator = PathInterpolator(0.17f, 0f, -0.15f, 1f)
                                    animator1.start()
                                }
                                animator.start()
                            }
                        }
                    }
                }
            }
        }
    }

    fun smoothScrollTo(position: Int, noAnimation: Boolean = false) {
        val smoothScroller = createSmoothScroller(noAnimation || forceNoAnimation)
        smoothScroller.targetPosition = position
        recyclerView?.layoutManager?.startSmoothScroll(
            smoothScroller
        )
    }

    fun onPrefsChanged() {
        updateLyricStatus()
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun updateLyricPositionFromPlaybackPos() {
        playbackPosition = callback?.getCurrentPosition() ?: 0uL
        val now = SystemClock.elapsedRealtime()
        val previous = lastPlaybackPosition
        val elapsed = (now - lastPlaybackUpdate).coerceAtLeast(0)
        val seek = previous != null && (playbackPosition < previous ||
            playbackPosition - previous > (elapsed * speed * 4 + 500).toULong())
        val animateBackground = previous != null && !seek && !forceNoAnimation &&
            callback?.isPlaying() == true
        lastPlaybackPosition = playbackPosition
        lastPlaybackUpdate = now
        val newStyles = lyricList.mapIndexedNotNull { index, lyric ->
            val line = lyric.line ?: return@mapIndexedNotNull null
            val character = lyric.longCharacters.lastOrNull {
                playbackPosition in it.timing.begin..it.timing.end
            }
            val speaker = lyric.speaker
            val scale = activeWordScale(
                character?.timing, playbackPosition, !forceNoAnimation,
                prefs.getIntStrict("lyric_word_expansion_duration", DEFAULT_WORD_EXPANSION_DURATION_MS),
                lyricSpringSpec(
                    prefs,
                    if (speaker?.isBackground == true) "background"
                    else if (speaker?.isVoice2 == true || speaker?.isGroup == true) "duet"
                    else "primary"
                )
            )
            if (lyric.isBackground || character != null)
                index to PlaybackStyle(
                    backgroundGroups.visible(line, playbackPosition),
                    lyric.isBackground && playbackPosition in line.timeRange,
                    character?.charRange?.first ?: -1, (scale * 100).toInt()
                )
            else null
        }.toMap()
        (playbackStyles.keys + newStyles.keys).forEach {
            if (playbackStyles[it] != newStyles[it]) {
                notifyItemChanged(it, PlaybackPayload(animateBackground))
            }
        }
        playbackStyles = newStyles
        if (lyricList.isNotEmpty()) {
            val newIndex = updateNewIndex()

            if (newIndex != -1 &&
                newIndex != currentFocusPos
            ) {
                if (lyricList[newIndex].content.isNotEmpty() || newIndex == 0) {
                    smoothScrollTo(newIndex, newIndex == 0)
                }

                updateHighlight(newIndex)
            }
        }
    }

    private fun updateNewIndex(): Int {
        return primaryLyricScrollIndex(
            lyricList.map { it.line }, callback?.getCurrentPosition() ?: 0uL
        ) ?: -1
    }

    fun updateLyrics(
        newLyric: SemanticLyrics?,
        attribution: SpicyLyricsAttribution? = lyricsAttribution
    ) {
        lyricsAttribution = attribution
        val parsedLyrics = newLyric.convertForLegacy()
        val updatedLyrics = parsedLyrics ?: mutableListOf(
            Lyric(null, context.getString(R.string.no_lyric_found))
        )
        buildAttributionLyric(attribution)?.let(updatedLyrics::add)
        if (lyricList != updatedLyrics) {
            playbackStyles = emptyMap()
            lastPlaybackPosition = null
            lyricList.clear()
            lyricList.addAll(updatedLyrics)
            backgroundGroups = LyricBackgroundGroups(lyricList.mapNotNull { it.line })
            @SuppressLint("NotifyDataSetChanged")
            notifyDataSetChanged()
            val initialFocus = updateNewIndex()
            if (initialFocus >= 0) {
                smoothScrollTo(initialFocus, initialFocus == 0)
                updateHighlight(initialFocus)
            } else {
                updateHighlight(-1)
            }
        }
    }

    fun updateTextColor(newColor: Int, newHighlightColor: Int, isCustomHighlight: Boolean = false) {
        defaultTextColor = newColor
        highlightTextColor = newHighlightColor
        customHighlight = isCustomHighlight
        @SuppressLint("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    private data class Lyric(
        val timeStamp: Long? = null,
        val content: CharSequence = "",
        var isTranslation: Boolean = false,
        var isAttributionFooter: Boolean = false,
        var endTime: Long? = null,
        var speaker: SpeakerEntity? = null,
        var isBackground: Boolean = speaker?.isBackground == true,
        val line: SemanticLyrics.LyricLine? = null,
        val longCharacters: List<LongLyricCharacter> = line?.let {
            prepareLongLyricWords(it.words, it.text, it.timeRange).flatMap { word ->
                prepareLongLyricCharacters(word, it.text)
            }
        }.orEmpty()
    )

    private fun buildAttributionLyric(attribution: SpicyLyricsAttribution?): Lyric? {
        attribution ?: return null
        return Lyric(content = buildLyricsAttribution(context, attribution), isAttributionFooter = true)
    }

    private fun SemanticLyrics?.convertForLegacy(): MutableList<Lyric>? {
        if (this == null) return null
        if (this is SyncedLyrics) {
            return this.text.map {
                Lyric(
                    timeStamp = it.start.toLong(),
                    content = it.text,
                    isTranslation = it.isTranslated,
                    endTime = it.end.toLong(),
                    speaker = it.speaker,
                    isBackground = it.speaker?.isBackground == true,
                    line = it
                )
            }.toMutableList()
        }
        return mutableListOf(
            Lyric(
                null,
            this.unsyncedText.joinToString("\n") { it.first }, false
        )
        )
    }

}
