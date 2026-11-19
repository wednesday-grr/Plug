/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.google.android.material.appbar.MaterialToolbar
import org.akanework.gramophone.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MusicStoreWebViewActivityTest {
    @Test
    fun opensSelectedStoreInWebViewAndBackFinishes() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, MusicStoreWebViewActivity::class.java)
            .putExtra(MusicStoreWebViewActivity.EXTRA_URL, "https://bandcamp.com")
            .putExtra(MusicStoreWebViewActivity.EXTRA_TITLE, "Bandcamp")
        val controller = Robolectric.buildActivity(MusicStoreWebViewActivity::class.java, intent).setup()
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.store_webview)
        val toolbar = activity.findViewById<MaterialToolbar>(R.id.store_toolbar)

        assertEquals("Bandcamp", toolbar.title)
        assertEquals("https://bandcamp.com", shadowOf(webView).lastLoadedUrl)
        assertTrue(webView.settings.javaScriptEnabled)
        assertTrue(webView.settings.domStorageEnabled)
        assertFalse(toolbar.menu.findItem(R.id.store_back).isEnabled)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        controller.destroy()
    }

    @Test
    fun keepsHttpInsideWebViewAndSanitizesExternalIntents() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, MusicStoreWebViewActivity::class.java)
            .putExtra(MusicStoreWebViewActivity.EXTRA_URL, "https://bandcamp.com")
        val controller = Robolectric.buildActivity(MusicStoreWebViewActivity::class.java, intent).setup()
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.store_webview)
        val client = webView.webViewClient

        assertFalse(client.shouldOverrideUrlLoading(webView, request("https://bandcamp.com/album")))
        assertTrue(client.shouldOverrideUrlLoading(webView, request(
            "intent://payment/#Intent;scheme=payapp;package=com.example.bank;S.secret=ignored;end"
        )))
        val external = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, external.action)
        assertEquals("payapp://payment/", external.data.toString())
        assertEquals("com.example.bank", external.`package`)
        assertTrue(external.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertFalse(external.hasExtra("secret"))
        controller.destroy()
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
