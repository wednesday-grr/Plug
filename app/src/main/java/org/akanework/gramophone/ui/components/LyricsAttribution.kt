package org.akanework.gramophone.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.utils.SpicyLyricsAttribution

internal val LYRICS_ATTRIBUTION_COLOR = 0xCCFFFFFF.toInt()

internal fun buildLyricsAttribution(context: Context, attribution: SpicyLyricsAttribution): SpannableStringBuilder {
    val provider = when (attribution.source.lowercase()) {
        "spicy_lyrics", "spicylyrics" -> "Spicy Lyrics"
        "apple_music" -> "Apple Music"
        "spotify" -> "Spotify"
        else -> attribution.source.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    val text = SpannableStringBuilder(context.getString(R.string.lyrics_provided_by, provider))
    if (attribution.songWriters.isNotEmpty()) {
        text.append('\n').append(
            context.getString(R.string.lyrics_songwriters, attribution.songWriters.joinToString(", "))
        )
    }
    fun appendCredit(label: Int, name: String?, url: String?) {
        if (name.isNullOrBlank()) return
        val formatted = context.getString(label, name)
        text.append('\n')
        val start = text.length
        text.append(formatted)
        val uri = url?.let(Uri::parse)
        if (uri == null || !uri.isHierarchical ||
            !(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) ||
            uri.host.isNullOrBlank()
        ) return
        val nameStart = formatted.indexOf(name)
        if (nameStart < 0) return
        text.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            override fun updateDrawState(ds: TextPaint) {
                ds.color = Color.WHITE
                ds.isUnderlineText = true
            }
        }, start + nameStart, start + nameStart + name.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    appendCredit(R.string.lyrics_uploader, attribution.uploaderName, attribution.uploaderUrl)
    appendCredit(R.string.lyrics_maker, attribution.makerName, attribution.makerUrl)
    return text
}
