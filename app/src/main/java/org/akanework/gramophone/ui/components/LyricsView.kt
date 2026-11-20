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

import android.content.Context
import android.content.SharedPreferences
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.BitmapDrawable
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.ImageView
import androidx.core.graphics.Insets
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.Player
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.dpToPx
import org.akanework.gramophone.logic.getBooleanStrict
import org.akanework.gramophone.logic.ui.MyRecyclerView
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SpicyLyricsAttribution
import org.akanework.gramophone.ui.MainActivity
import androidx.core.view.isVisible
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowConversionToBitmap
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.request.Disposable

internal const val LYRICS_ART_ALPHA = 0.56f
internal const val LYRICS_ART_BRIGHTNESS = 0.7f
internal const val LYRICS_SCRIM_COLOR = 0x66000000

internal data class LyricsTheme(val surface: Int, val inactive: Int, val active: Int, val translation: Int)

internal fun darkenLyricsSurface(color: Int, brightness: Float = 0.7f, maxValue: Float = 0.45f): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(color, hsv)
    hsv[2] = (hsv[2] * brightness).coerceAtMost(maxValue)
    return Color.HSVToColor(Color.alpha(color), hsv)
}

internal fun lyricsArtworkColorFilter(brightness: Float = LYRICS_ART_BRIGHTNESS) = ColorMatrixColorFilter(floatArrayOf(
    brightness, 0f, 0f, 0f, 0f,
    0f, brightness, 0f, 0f, 0f,
    0f, 0f, brightness, 0f, 0f,
    0f, 0f, 0f, 1f, 0f
))

class LyricsView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private var recyclerView: MyRecyclerView? = null
    private var newView: NewLyricsView? = null
    private var incomingContainer: FrameLayout? = null
    private var incomingView: NewLyricsView? = null
    private var incomingAdapter: LegacyLyricsAdapter? = null
    private var splitAnimator: ValueAnimator? = null
    private var splitAmount = 0f
    private var incomingId: String? = null
    private var incomingLyrics: SemanticLyrics? = null
    private var incomingAttribution: SpicyLyricsAttribution? = null
    private var incomingLyricsReady = false
    private var incomingPosition = 0L
    private var incomingSampleTime = 0L
    private var incomingPlaying = false
    private var incomingSpeed = 1f
    private var outgoingPosition = 0L
    private var outgoingSampleTime = 0L
    private var outgoingPlaying = false
    private var outgoingSpeed = 1f
    private var endingSplit = false
    private var splitHandoff = false
    private var handoffStartAmount = 1f
    private var incomingLyricsFromPlayer = false
    private var incomingContent: View? = null
    private var outgoingTheme: LyricsTheme? = null
    private var fallbackIncomingTheme: LyricsTheme? = null
    private var incomingTheme: LyricsTheme? = null
    private var incomingArtworkUri: Uri? = null
    private var incomingArtworkRequest: Disposable? = null
    private var incomingPaletteRequest: Disposable? = null
    private var incomingArtworkGeneration = 0L
    private var incomingThemeJob: Job? = null
    private var incomingArtwork: ImageView? = null
    private var incomingScrim: View? = null
    private var outgoingArtworkLuminance: Float? = null
    private var incomingArtworkLuminance: Float? = null
    private var backgroundArtworkBrightnessScale: Float? = null
    private val albumArtBackgrounds = mutableListOf<ImageView>()
    private var backgroundScrim: View? = null
    private var backgroundRequest: Disposable? = null
    private var requestedArtworkUri: Uri? = null
    private var lyricSurfaceColor = (background as? ColorDrawable)?.color ?: Color.BLACK
    private var appliedTextColors: Triple<Int, Int, Int>? = null
    private val adapter
        get() = recyclerView?.adapter as LegacyLyricsAdapter?
    var defaultTextColor = 0
        private set
    var highlightTextColor = 0
        private set
    var highlightTlTextColor = 0
        private set
    private var lyrics: SemanticLyrics? = null
    private var lyricsAttribution: SpicyLyricsAttribution? = null
    private var contentPaddingLeft = 0
    private var contentPaddingTop = 0
    private var contentPaddingRight = 0
    private var contentPaddingBottom = 0
    private val fullPlayer by lazy { (parent.parent as ViewGroup)
        .findViewById<FullBottomSheet>(R.id.full_player)!! }

    private fun currentTheme() =
        LyricsTheme(lyricSurfaceColor, defaultTextColor, highlightTextColor, highlightTlTextColor)

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val myInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(myInsets.left, myInsets.top, myInsets.right, myInsets.bottom)
            return@setOnApplyWindowInsetsListener WindowInsetsCompat.Builder(insets)
                .setInsets(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout(), Insets.NONE
                )
                .setInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout(), Insets.NONE
                )
                .build()
        }
        createView()
    }

    private fun createView() {
        clearIncomingArtwork()
        outgoingTheme = null
        val oldAnimator = splitAnimator
        splitAnimator = null
        oldAnimator?.cancel()
        incomingAdapter?.callback?.destroy()
        incomingView?.instance?.destroy()
        incomingContainer = null
        incomingView = null
        incomingAdapter = null
        incomingContent = null
        incomingId = null
        splitAmount = 0f
        endingSplit = false
        albumArtBackgrounds.forEach { it.animate().cancel() }
        removeAllViews()
        val oldPaddingTop = newView?.paddingTop ?: recyclerView?.paddingTop ?: 0
        val oldPaddingBottom = newView?.paddingBottom ?: recyclerView?.paddingBottom ?: 0
        val oldPaddingLeft = newView?.paddingLeft ?: recyclerView?.paddingLeft ?: 0
        val oldPaddingRight = newView?.paddingRight ?: recyclerView?.paddingRight ?: 0
        contentPaddingLeft = oldPaddingLeft
        contentPaddingTop = oldPaddingTop
        contentPaddingRight = oldPaddingRight
        contentPaddingBottom = oldPaddingBottom
        adapter?.callback?.destroy()
        newView?.instance?.destroy()
        recyclerView = null
        newView = null
        appliedTextColors = null
        backgroundRequest?.dispose()
        backgroundRequest = null
        requestedArtworkUri = null
        createBackgroundLayers()
        val cb = object : NewLyricsView.Callbacks, Player.Listener, LifecycleOwner {
            private val activePlayer
                get() = (context as MainActivity).getPlayer()
            override val lifecycle = LifecycleRegistry(this)

            init {
                lifecycle.currentState = Lifecycle.State.CREATED
                (context as MainActivity).controllerViewModel.addRecreationalPlayerListener(
                    lifecycle, this) {}
            }

            override fun getCurrentPosition(): ULong =
                if (incomingId != null) sampledPosition(
                    outgoingPosition, outgoingSampleTime, outgoingPlaying, outgoingSpeed
                ) else activePlayer?.currentPosition?.coerceAtLeast(0L)?.toULong() ?: 0uL

            override fun isPlaying() = if (incomingId != null) outgoingPlaying else activePlayer?.isPlaying == true

            override fun speed(): Float {
                return if (incomingId != null) outgoingSpeed else activePlayer?.playbackParameters?.speed ?: 1f
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateLyricPositionFromPlaybackPos()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: @Player.DiscontinuityReason Int
            ) {
                updateLyricPositionFromPlaybackPos()
            }

            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                updateAlbumArtBackground()
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                updateAlbumArtBackground()
            }

            override fun destroy() {
                lifecycle.currentState = Lifecycle.State.DESTROYED
            }
        }
        if (prefs.getBooleanStrict("lyric_ui_v2", true)) {
            inflate(context, R.layout.lyric_view_v2, this)
            newView = findViewById(R.id.lyric_view)!!
            newView!!.setPadding(oldPaddingLeft, oldPaddingTop, oldPaddingRight, oldPaddingBottom)
            newView!!.instance = cb
            newView!!.updateLyrics(lyrics)
            newView!!.updateAttribution(lyricsAttribution)
        } else {
            inflate(context, R.layout.lyric_view, this)
            recyclerView = findViewById(R.id.recycler_view)
            recyclerView?.setPadding(
                oldPaddingLeft,
                oldPaddingTop,
                oldPaddingRight,
                oldPaddingBottom
            )
            recyclerView!!.adapter = LegacyLyricsAdapter(context)
            recyclerView!!.addItemDecoration(LyricPaddingDecoration(context))
            adapter!!.callback = cb
            adapter!!.updateLyrics(lyrics, lyricsAttribution)
        }
        applyTextColors()
        updateAlbumArtBackground()
    }

    private fun createBackgroundLayers() {
        albumArtBackgrounds.clear()
        repeat(2) {
            val art = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0f
                visibility = GONE
                colorFilter = lyricsArtworkColorFilter()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val radius = 48f.dpToPx(context)
                    setRenderEffect(RenderEffect.createBlurEffect(
                        radius, radius, Shader.TileMode.CLAMP
                    ))
                }
            }
            albumArtBackgrounds.add(art)
            addView(art, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        val scrim = View(context).apply {
            setBackgroundColor(LYRICS_SCRIM_COLOR)
            visibility = GONE
        }
        backgroundScrim = scrim
        addView(
            scrim,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun updateAlbumArtBackground() {
        if (incomingId != null) return
        val enabled = prefs.getBooleanStrict("lyric_apple_music_effects", true)
        val artworkUri = if (enabled) {
            (context as? MainActivity)?.getPlayer()?.currentMediaItem?.mediaMetadata?.artworkUri
        } else null
        applySurfaceColor()
        backgroundScrim?.visibility = if (enabled) VISIBLE else GONE
        if (artworkUri != null && artworkUri == requestedArtworkUri) return
        backgroundRequest?.dispose()
        backgroundRequest = null
        requestedArtworkUri = artworkUri
        if (!enabled) {
            albumArtBackgrounds.forEach {
                it.animate().cancel()
                it.alpha = 0f
                it.visibility = GONE
                it.setImageDrawable(null)
            }
            return
        }
        if (artworkUri == null) {
            fadeOutArtwork()
            return
        }
        backgroundRequest = context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(artworkUri)
                .size(480, 800)
                .scale(Scale.FILL)
                .allowConversionToBitmap(true)
                .allowHardware(false)
                .target(onSuccess = { drawable ->
                    albumArtBackgrounds.forEach { it.animate().cancel() }
                    backgroundArtworkBrightnessScale = null
                    applySurfaceColor()
                    val oldArt = albumArtBackgrounds.maxBy { it.alpha }
                    val newArt = albumArtBackgrounds.first { it !== oldArt }
                    newArt.alpha = 0f
                    newArt.setImageDrawable(drawable.asDrawable(context.resources))
                    newArt.visibility = VISIBLE
                    newArt.animate().alpha(LYRICS_ART_ALPHA)
                        .setDuration(FullBottomSheet.BACKGROUND_COLOR_TRANSITION_SEC).start()
                    fadeOutArtwork(oldArt)
                }, onError = {
                    requestedArtworkUri = null
                    fadeOutArtwork()
                })
                .build()
        )
    }

    private fun fadeOutArtwork(vararg views: ImageView = albumArtBackgrounds.toTypedArray()) {
        views.forEach { art ->
            art.animate().cancel()
            art.animate().alpha(0f)
                .setDuration(FullBottomSheet.BACKGROUND_COLOR_TRANSITION_SEC)
                .withEndAction {
                    art.visibility = GONE
                    art.setImageDrawable(null)
                }.start()
        }
    }

    private fun transitionArtworkBrightness(baseBrightness: Float): Pair<Float, Float> {
        val outgoing = outgoingArtworkLuminance
        val incoming = incomingArtworkLuminance
        if (outgoing == null || outgoing <= 0f || incoming == null || incoming <= 0f) {
            val retained = backgroundArtworkBrightnessScale?.let { baseBrightness * it }
                ?: baseBrightness
            return retained to baseBrightness
        }
        val target = minOf(outgoing, incoming) * baseBrightness
        return (target / outgoing) to (target / incoming)
    }

    private fun normalizedIncomingArtworkScale(): Float? {
        val outgoing = outgoingArtworkLuminance ?: return null
        val incoming = incomingArtworkLuminance ?: return null
        if (outgoing <= 0f || incoming <= 0f) return null
        return minOf(outgoing, incoming) / incoming
    }

    private fun artworkLuminance(drawable: android.graphics.drawable.Drawable?): Float? {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val stepX = (bitmap.width / 32).coerceAtLeast(1)
        val stepY = (bitmap.height / 32).coerceAtLeast(1)
        var luminance = 0L
        var samples = 0
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val color = bitmap.getPixel(x, y)
                luminance += 2126L * Color.red(color) +
                    7152L * Color.green(color) + 722L * Color.blue(color)
                samples++
            }
        }
        return if (samples == 0) null else luminance.toFloat() / (10000f * 255f * samples)
    }

    fun setLyricBackgroundColor(color: Int) {
        lyricSurfaceColor = color
        applySurfaceColor()
    }

    private fun applySurfaceColor() {
        val brightness = if (prefs.getBoolean("lyric_custom_brightness_enabled", false))
            prefs.getInt("lyric_custom_brightness", 70).coerceIn(0, 100) / 100f
        else LYRICS_ART_BRIGHTNESS
        val (outgoingBrightness, incomingBrightness) = if (incomingId != null) {
            transitionArtworkBrightness(brightness)
        } else {
            ((backgroundArtworkBrightnessScale?.let { brightness * it }) ?: brightness) to brightness
        }
        albumArtBackgrounds.forEach {
            it.colorFilter = lyricsArtworkColorFilter(outgoingBrightness)
        }
        val color = surfaceColor(outgoingTheme?.surface ?: lyricSurfaceColor)
        setBackgroundColor(color)
        incomingContainer?.setBackgroundColor(surfaceColor(
            (incomingTheme ?: fallbackIncomingTheme ?: currentTheme()).surface
        ))
        incomingArtwork?.colorFilter = lyricsArtworkColorFilter(incomingBrightness)
        val enabled = prefs.getBooleanStrict("lyric_apple_music_effects", true)
        incomingArtwork?.visibility = if (enabled && incomingArtwork?.drawable != null) VISIBLE else GONE
        incomingScrim?.visibility = if (enabled) VISIBLE else GONE
    }

    private fun surfaceColor(color: Int): Int {
        if (!prefs.getBooleanStrict("lyric_apple_music_effects", true)) return color
        val custom = prefs.getBoolean("lyric_custom_brightness_enabled", false)
        val brightness = if (custom) prefs.getInt("lyric_custom_brightness", 70).coerceIn(0, 100) / 100f
        else LYRICS_ART_BRIGHTNESS
        return darkenLyricsSurface(color, brightness, if (custom) 1f else 0.45f)
    }

    private fun updateFullPlayerVisibility() {
        fullPlayer.visibilityDueToLyrics = if (isVisible && alpha == 1f && scaleX == 1f &&
            scaleY == 1f && translationX == 0f && translationY == 0f && !hasTransientState())
                GONE else VISIBLE
    }

    override fun setHasTransientState(hasTransientState: Boolean) {
        super.setHasTransientState(hasTransientState)
        updateFullPlayerVisibility()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        updateFullPlayerVisibility()
    }

    override fun setAlpha(alpha: Float) {
        super.setAlpha(alpha)
        updateFullPlayerVisibility()
    }

    override fun setScaleX(scaleX: Float) {
        super.setScaleX(scaleX)
        updateFullPlayerVisibility()
    }

    override fun setScaleY(scaleY: Float) {
        super.setScaleY(scaleY)
        updateFullPlayerVisibility()
    }

    override fun setTranslationX(translationX: Float) {
        super.setTranslationX(translationX)
        updateFullPlayerVisibility()
    }

    override fun setTranslationY(translationY: Float) {
        super.setTranslationY(translationY)
        updateFullPlayerVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(this)
        adapter?.updateLyricStatus()
        if (incomingId != null) loadIncomingArtwork(incomingArtworkUri, force = true)
        updateFullPlayerVisibility()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        incomingArtworkRequest?.dispose()
        incomingArtworkRequest = null
        incomingPaletteRequest?.dispose()
        incomingPaletteRequest = null
        incomingArtworkGeneration++
        incomingThemeJob?.cancel()
        incomingThemeJob = null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "lyric_center" || key == "lyric_no_animation" ||
            key == "lyric_apple_music_effects" || key == "lyric_word_expansion_duration"
        ) {
            adapter?.onPrefsChanged()
            incomingAdapter?.onPrefsChanged()
        }
        if (key == "lyric_center" || key == "lyric_no_animation" ||
            key == "translation_auto_word" || key == "lyric_text_size" ||
            key == "lyric_apple_music_effects" || key == "lyric_word_expansion_duration"
        )
        {
            newView?.onPrefsChanged(key)
            incomingView?.onPrefsChanged(key)
        }
        else if (key == "lyric_ui_v2")
            createView()
        if (key == "lyric_apple_music_effects")
            updateAlbumArtBackground()
        if (key == "lyric_apple_music_effects" || key == "content_based_color" ||
            key == "color_accuracy" || key == "pureDark"
        ) {
            if (incomingId != null) loadIncomingArtwork(incomingArtworkUri, force = true)
            applySurfaceColor()
        }
        if (key == "lyric_custom_active_enabled" || key == "lyric_custom_active_color" ||
            key == "lyric_custom_inactive_enabled" || key == "lyric_custom_inactive_color"
        ) {
            appliedTextColors = null
            applyTextColors()
        }
        if (key == "lyric_custom_brightness_enabled" || key == "lyric_custom_brightness")
            applySurfaceColor()
    }

    fun updateLyricPositionFromPlaybackPos() {
        adapter?.updateLyricPositionFromPlaybackPos()
        newView?.updateLyricPositionFromPlaybackPos()
        incomingAdapter?.updateLyricPositionFromPlaybackPos()
        incomingView?.updateLyricPositionFromPlaybackPos()
    }

    fun updateLyrics(parsedLyrics: SemanticLyrics?) {
        lyrics = parsedLyrics
        if (incomingId != null &&
            (context as? MainActivity)?.getPlayer()?.currentMediaItem?.mediaId == incomingId
        ) {
            incomingLyricsFromPlayer = true
            return
        }
        adapter?.updateLyrics(lyrics, lyricsAttribution)
        newView?.updateLyrics(lyrics)
    }

    fun updateLyrics(parsedLyrics: SemanticLyrics?, attribution: SpicyLyricsAttribution?) {
        lyricsAttribution = attribution
        updateLyrics(parsedLyrics)
        if (incomingId == null ||
            (context as? MainActivity)?.getPlayer()?.currentMediaItem?.mediaId != incomingId
        ) newView?.updateAttribution(attribution)
    }

    private fun sampledPosition(position: Long, sampleTime: Long, playing: Boolean, speed: Float): ULong {
        val elapsed = if (playing) (SystemClock.elapsedRealtime() - sampleTime).coerceAtLeast(0) else 0
        return (position + (elapsed * speed).toLong()).coerceAtLeast(0).toULong()
    }

    fun updateAutomixLyrics(args: Bundle) {
        if (!args.getBoolean("fading")) {
            if (incomingId == null) return
            outgoingPosition = sampledPosition(
                outgoingPosition, outgoingSampleTime, outgoingPlaying, outgoingSpeed
            ).toLong()
            outgoingPlaying = false
            incomingPosition = sampledPosition(
                incomingPosition, incomingSampleTime, incomingPlaying, incomingSpeed
            ).toLong()
            incomingSampleTime = SystemClock.elapsedRealtime()
            endingSplit = true
            animateSplit(0f, args.getBoolean("handoff"))
            return
        }
        val id = args.getString("incoming_id") ?: return
        if (incomingId != id || incomingContainer == null) {
            if (outgoingTheme == null) {
                outgoingTheme = currentTheme()
                outgoingArtworkLuminance = albumArtBackgrounds
                    .filter { it.drawable != null }
                    .maxByOrNull { it.alpha }
                    ?.let { artworkLuminance(it.drawable) }
                incomingArtworkLuminance = null
                backgroundRequest?.dispose()
                backgroundRequest = null
                requestedArtworkUri = null
                albumArtBackgrounds.forEach { it.animate().cancel() }
            }
            fallbackIncomingTheme = baseTheme()
            incomingId = id
            incomingLyrics = null
            incomingAttribution = null
            incomingLyricsReady = false
            incomingLyricsFromPlayer = false
            endingSplit = false
            createIncomingRenderer()
            loadIncomingArtwork(args.getString("incoming_artwork")?.let(Uri::parse), force = true)
            animateSplit(1f, false)
        } else if (endingSplit) {
            endingSplit = false
            animateSplit(1f, false)
        }
        val artworkUri = args.getString("incoming_artwork")?.let(Uri::parse)
        if (artworkUri != incomingArtworkUri) loadIncomingArtwork(artworkUri, force = true)
        incomingPosition = args.getLong("position")
        incomingSampleTime = args.getLong("sample_time")
        incomingPlaying = args.getBoolean("playing")
        incomingSpeed = args.getFloat("speed", 1f)
        outgoingPosition = args.getLong("outgoing_position")
        outgoingSampleTime = incomingSampleTime
        outgoingPlaying = args.getBoolean("outgoing_playing")
        outgoingSpeed = args.getFloat("outgoing_speed", 1f)
        if (args.getBoolean("lyrics_updated")) {
            val newLyrics = BundleCompat.getParcelable(args, "lyrics", SemanticLyrics::class.java)
            val newAttribution = BundleCompat.getParcelable(args, "attribution", SpicyLyricsAttribution::class.java)
            val changed = !incomingLyricsReady || incomingLyrics != newLyrics || incomingAttribution != newAttribution
            incomingLyrics = newLyrics
            incomingAttribution = newAttribution
            incomingLyricsReady = true
            if (changed) {
                incomingView?.updateLyrics(incomingLyrics)
                incomingView?.updateAttribution(incomingAttribution)
                incomingAdapter?.updateLyrics(incomingLyrics, incomingAttribution)
            }
        }
        updateLyricPositionFromPlaybackPos()
    }

    private fun createIncomingRenderer() {
        incomingAdapter?.callback?.destroy()
        incomingView?.instance?.destroy()
        incomingContainer?.let(::removeView)
        val container = FrameLayout(context)
        incomingContainer = container
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        incomingArtwork = ImageView(context).also { art ->
            art.scaleType = ImageView.ScaleType.CENTER_CROP
            art.alpha = LYRICS_ART_ALPHA
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val radius = 48f.dpToPx(context)
                art.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            }
            container.addView(art, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        incomingScrim = View(context).also { scrim ->
            scrim.setBackgroundColor(LYRICS_SCRIM_COLOR)
            container.addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val callbacks = object : NewLyricsView.Callbacks {
            override fun getCurrentPosition() = sampledPosition(
                incomingPosition, incomingSampleTime, incomingPlaying, incomingSpeed
            )
            override fun isPlaying() = incomingPlaying
            override fun speed() = incomingSpeed
            override fun destroy() {}
        }
        if (prefs.getBooleanStrict("lyric_ui_v2", true)) {
            inflate(context, R.layout.lyric_view_v2, container)
            incomingView = container.findViewById<NewLyricsView>(R.id.lyric_view).also {
                it.instance = callbacks
                it.setPadding(contentPaddingLeft, contentPaddingTop, contentPaddingRight, contentPaddingBottom)
                it.updateLyrics(null)
            }
            incomingContent = incomingView
            incomingAdapter = null
        } else {
            inflate(context, R.layout.lyric_view, container)
            val list = container.findViewById<MyRecyclerView>(R.id.recycler_view)
            list.setPadding(contentPaddingLeft, contentPaddingTop, contentPaddingRight, contentPaddingBottom)
            list.addItemDecoration(LyricPaddingDecoration(context))
            incomingAdapter = LegacyLyricsAdapter(context).also {
                it.callback = callbacks
                list.adapter = it
                it.updateLyrics(null, null)
            }
            incomingContent = list
            incomingView = null
        }
        appliedTextColors = null
        applyTextColors()
        applySurfaceColor()
        layoutSplit()
    }

    private fun clearIncomingArtwork() {
        incomingArtworkRequest?.dispose()
        incomingArtworkRequest = null
        incomingPaletteRequest?.dispose()
        incomingPaletteRequest = null
        incomingArtworkGeneration++
        incomingThemeJob?.cancel()
        incomingThemeJob = null
        incomingArtworkUri = null
        incomingArtwork = null
        incomingScrim = null
        incomingTheme = null
        fallbackIncomingTheme = null
        outgoingArtworkLuminance = null
        incomingArtworkLuminance = null
    }

    private fun loadIncomingArtwork(uri: Uri?, force: Boolean = false) {
        if (!force && uri == incomingArtworkUri) return
        incomingArtworkRequest?.dispose()
        incomingPaletteRequest?.dispose()
        incomingThemeJob?.cancel()
        val generation = ++incomingArtworkGeneration
        incomingArtworkUri = uri
        incomingTheme = null
        incomingArtworkLuminance = null
        incomingArtwork?.setImageDrawable(null)
        applySurfaceColor()
        appliedTextColors = null
        applyTextColors()
        if (uri == null) return
        val trackId = incomingId
        incomingArtworkRequest = context.imageLoader.enqueue(
            ImageRequest.Builder(context).data(uri).size(480, 800).scale(Scale.FILL)
                .allowConversionToBitmap(true).allowHardware(false)
                .target(onSuccess = { image ->
                    if (generation != incomingArtworkGeneration) return@target
                    val drawable = image.asDrawable(context.resources)
                    incomingArtwork?.setImageDrawable(drawable)
                    incomingArtworkLuminance = artworkLuminance(drawable)
                    applySurfaceColor()
                }, onError = {
                    android.util.Log.w("LyricsView", "Unable to load incoming album artwork")
                }).build()
        )
        if (DynamicColors.isDynamicColorAvailable() && prefs.getBooleanStrict("content_based_color", true)) {
            val size = if (prefs.getBoolean("color_accuracy", false)) 256 else 16
            incomingPaletteRequest = context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(uri).size(size, size).scale(Scale.FILL)
                    .allowConversionToBitmap(true).allowHardware(false)
                    .target(onSuccess = { image ->
                        if (generation != incomingArtworkGeneration) return@target
                        val bitmap = (image.asDrawable(context.resources) as? BitmapDrawable)?.bitmap
                        if (bitmap == null) {
                            android.util.Log.w("LyricsView", "Incoming artwork cannot be used for dynamic colors")
                            return@target
                        }
                        incomingThemeJob = CoroutineScope(Dispatchers.Default).launch {
                            val options = DynamicColorsOptions.Builder().setContentBasedSource(bitmap).build()
                            val theme = incomingThemeFromOptions(options)
                            withContext(Dispatchers.Main) {
                                if (generation == incomingArtworkGeneration && trackId == incomingId) {
                                    incomingTheme = theme
                                    applySurfaceColor()
                                    appliedTextColors = null
                                    applyTextColors()
                                }
                            }
                        }
                    }, onError = {
                        android.util.Log.w("LyricsView", "Unable to extract incoming album colors")
                    }).build()
            )
        }
    }

    private fun incomingThemeFromOptions(options: DynamicColorsOptions): LyricsTheme {
        val wrapped = DynamicColors.wrapContextIfAvailable(context, options).apply {
            resources.configuration.uiMode = context.resources.configuration.uiMode
        }.let {
            if (prefs.getBoolean("pureDark", false) &&
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            ) ContextThemeWrapper(it, R.style.ThemeOverlay_PureDark) else it
        }
        return themeFromContext(wrapped)
    }

    private fun baseTheme(): LyricsTheme = themeFromContext(context)

    private fun themeFromContext(wrapped: Context): LyricsTheme {
        val surface = MaterialColors.getColor(wrapped, com.google.android.material.R.attr.colorSurface, Color.BLACK)
        val processed = org.akanework.gramophone.logic.utils.ColorUtils.getColor(
            surface, org.akanework.gramophone.logic.utils.ColorUtils.ColorType.COLOR_BACKGROUND_ELEVATED, wrapped
        )
        val primary = MaterialColors.getColor(wrapped, androidx.appcompat.R.attr.colorPrimary, Color.WHITE)
        val inactive = androidx.core.graphics.ColorUtils.compositeColors(
            androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 77), processed
        )
        val translation = androidx.core.graphics.ColorUtils.compositeColors(
            androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 200), processed
        )
        return LyricsTheme(processed, inactive, primary, translation)
    }

    private fun animateSplit(target: Float, handoff: Boolean) {
        val oldAnimator = splitAnimator
        splitAnimator = null
        oldAnimator?.cancel()
        splitHandoff = handoff
        if (handoff) handoffStartAmount = splitAmount
        if (prefs.getBooleanStrict("lyric_no_animation", false)) {
            splitAmount = target
            layoutSplit(handoff)
            if (target == 0f) finishSplit(handoff)
            return
        }
        val animator = ValueAnimator.ofFloat(splitAmount, target)
        splitAnimator = animator
        animator.duration = 400
        animator.interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
        animator.addUpdateListener {
            splitAmount = it.animatedValue as Float
            layoutSplit(handoff)
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (splitAnimator !== animator) return
                splitAnimator = null
                if (target == 0f) finishSplit(handoff)
            }
        })
        animator.start()
    }

    private fun layoutSplit(handoff: Boolean = false) {
        val total = height.coerceAtLeast(0)
        val splitHeight = (total * (1f - splitAmount / 2f)).toInt()
        val handoffStartHeight = (total * (1f - handoffStartAmount / 2f)).toInt()
        val handoffProgress = if (handoffStartAmount > 0f)
            (1f - splitAmount / handoffStartAmount).coerceIn(0f, 1f) else 1f
        val incomingTop = if (handoff)
            (handoffStartHeight * (1f - handoffProgress)).toInt() else splitHeight
        val main = newView ?: recyclerView
        main?.let {
            val params = it.layoutParams as? LayoutParams
            if (params == null || params.width != LayoutParams.MATCH_PARENT ||
                params.height != LayoutParams.MATCH_PARENT || params.topMargin != 0
            ) {
                it.layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
                )
            }
            val clipBottom: Int
            if (handoff) {
                it.translationY = -handoffStartHeight * handoffProgress
                clipBottom = handoffStartHeight
            } else {
                it.translationY = 0f
                clipBottom = splitHeight
            }
            it.clipBounds = if (incomingContainer != null && width > 0 && total > 0) {
                Rect(0, 0, width, clipBottom.coerceIn(0, total))
            } else null
        }
        incomingContainer?.let {
            val params = it.layoutParams as? LayoutParams
            if (params == null || params.width != LayoutParams.MATCH_PARENT ||
                params.height != LayoutParams.MATCH_PARENT || params.topMargin != 0
            ) {
                it.layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
                )
            }
            it.clipBounds = if (width > 0 && total > 0)
                Rect(0, incomingTop.coerceIn(0, total), width, total)
            else null
            incomingContent?.translationY = incomingTop.toFloat()
        }
    }

    private fun finishSplit(handoff: Boolean) {
        if (handoff) {
            incomingView?.let { newView?.prepareAutomixHandoffFrom(it) }
        }
        if (handoff && !incomingLyricsFromPlayer) {
            lyrics = if (incomingLyricsReady) incomingLyrics else null
            lyricsAttribution = if (incomingLyricsReady) incomingAttribution else null
        }
        if (handoff) {
            (incomingTheme ?: fallbackIncomingTheme)?.let {
                lyricSurfaceColor = it.surface
                defaultTextColor = it.inactive
                highlightTextColor = it.active
                highlightTlTextColor = it.translation
            }
            val artwork = incomingArtwork?.drawable
            if (artwork != null && incomingArtworkUri != null) {
                backgroundArtworkBrightnessScale = normalizedIncomingArtworkScale()
                backgroundRequest?.dispose()
                backgroundRequest = null
                albumArtBackgrounds.forEach {
                    it.animate().cancel()
                    it.setImageDrawable(null)
                    it.visibility = GONE
                    it.alpha = 0f
                }
                albumArtBackgrounds.firstOrNull()?.apply {
                    setImageDrawable(artwork)
                    alpha = LYRICS_ART_ALPHA
                    visibility = VISIBLE
                }
                requestedArtworkUri = incomingArtworkUri
            }
        }
        clearIncomingArtwork()
        outgoingTheme = null
        incomingContainer?.let(::removeView)
        incomingContainer = null
        incomingAdapter = null
        incomingView = null
        incomingContent = null
        incomingId = null
        endingSplit = false
        splitAmount = 0f
        layoutSplit()
        adapter?.updateLyrics(lyrics, lyricsAttribution)
        newView?.updateLyrics(lyrics)
        newView?.updateAttribution(lyricsAttribution)
        applySurfaceColor()
        appliedTextColors = null
        applyTextColors()
        updateAlbumArtBackground()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (incomingContainer != null) layoutSplit(splitHandoff)
    }

    private fun applyRendererPadding() {
        recyclerView?.setPadding(contentPaddingLeft, contentPaddingTop, contentPaddingRight, contentPaddingBottom)
        newView?.setPadding(contentPaddingLeft, contentPaddingTop, contentPaddingRight, contentPaddingBottom)
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        contentPaddingLeft = left
        contentPaddingTop = top
        contentPaddingRight = right
        contentPaddingBottom = bottom
        applyRendererPadding()
    }

    fun updateTextColor(
        newColor: Int, newHighlightColor: Int, newHighlightTlColor: Int
    ) {
        defaultTextColor = newColor
        highlightTextColor = newHighlightColor
        highlightTlTextColor = newHighlightTlColor
        applyTextColors()
    }

    fun updateTextColor(newColor: Int) {
        defaultTextColor = newColor
        applyTextColors()
    }

    fun updateHighlightColor(newHighlightColor: Int) {
        highlightTextColor = newHighlightColor
        applyTextColors()
    }

    fun updateHighlightTlColor(newHighlightTlColor: Int) {
        highlightTlTextColor = newHighlightTlColor
        applyTextColors()
    }

    private fun applyTextColors() {
        val active = customLyricColor(prefs, true)
        val customInactive = customLyricColor(prefs, false)
        val mainTheme = outgoingTheme ?: currentTheme()
        val inactive = customInactive ?: mainTheme.inactive
        val highlight = active ?: mainTheme.active
        val translation = active ?: mainTheme.translation
        val colors = Triple(inactive, highlight, translation)
        if (appliedTextColors != colors) {
            appliedTextColors = colors
            adapter?.updateTextColor(inactive, highlight, active != null)
            newView?.setCustomActiveColor(active)
            newView?.updateTextColor(inactive, highlight, translation)
        }
        val next = incomingTheme ?: fallbackIncomingTheme ?: currentTheme()
        val nextInactive = customInactive ?: next.inactive
        val nextHighlight = active ?: next.active
        val nextTranslation = active ?: next.translation
        incomingAdapter?.updateTextColor(nextInactive, nextHighlight, active != null)
        incomingView?.setCustomActiveColor(active)
        incomingView?.updateTextColor(nextInactive, nextHighlight, nextTranslation)
    }
}
