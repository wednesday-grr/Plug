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
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.Player
import androidx.preference.PreferenceManager
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
                activePlayer?.currentPosition?.coerceAtLeast(0L)?.toULong() ?: 0uL

            override fun isPlaying() = activePlayer?.isPlaying == true

            override fun speed(): Float {
                return activePlayer?.playbackParameters?.speed ?: 1f
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

    fun setLyricBackgroundColor(color: Int) {
        lyricSurfaceColor = color
        applySurfaceColor()
    }

    private fun applySurfaceColor() {
        val custom = prefs.getBoolean("lyric_custom_brightness_enabled", false)
        val brightness = if (custom)
            prefs.getInt("lyric_custom_brightness", 70).coerceIn(0, 100) / 100f
        else LYRICS_ART_BRIGHTNESS
        albumArtBackgrounds.forEach { it.colorFilter = lyricsArtworkColorFilter(brightness) }
        val color = if (prefs.getBooleanStrict("lyric_apple_music_effects", true)) {
            darkenLyricsSurface(lyricSurfaceColor, brightness, if (custom) 1f else 0.45f)
        } else lyricSurfaceColor
        setBackgroundColor(color)
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
        updateFullPlayerVisibility()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "lyric_center" || key == "lyric_apple_music_effects")
            adapter?.onPrefsChanged()
        if (key == "lyric_center" || key == "lyric_no_animation" ||
            key == "translation_auto_word" || key == "lyric_text_size" ||
            key == "lyric_apple_music_effects"
        )
            newView?.onPrefsChanged(key)
        else if (key == "lyric_ui_v2")
            createView()
        if (key == "lyric_apple_music_effects")
            updateAlbumArtBackground()
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
    }

    fun updateLyrics(parsedLyrics: SemanticLyrics?) {
        lyrics = parsedLyrics
        adapter?.updateLyrics(lyrics, lyricsAttribution)
        newView?.updateLyrics(lyrics)
    }

    fun updateLyrics(parsedLyrics: SemanticLyrics?, attribution: SpicyLyricsAttribution?) {
        lyricsAttribution = attribution
        updateLyrics(parsedLyrics)
        newView?.updateAttribution(attribution)
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
        val inactive = customLyricColor(prefs, false) ?: defaultTextColor
        val highlight = active ?: highlightTextColor
        val translation = active ?: highlightTlTextColor
        val colors = Triple(inactive, highlight, translation)
        if (appliedTextColors == colors) return
        appliedTextColors = colors
        adapter?.updateTextColor(inactive, highlight, active != null)
        newView?.setCustomActiveColor(active)
        newView?.updateTextColor(inactive, highlight, translation)
    }
}
