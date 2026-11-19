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
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
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
import org.robolectric.shadows.ShadowDialog

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
    fun upgradesHttpAndSanitizesExternalIntents() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, MusicStoreWebViewActivity::class.java)
            .putExtra(MusicStoreWebViewActivity.EXTRA_URL, "https://bandcamp.com")
        val controller = Robolectric.buildActivity(MusicStoreWebViewActivity::class.java, intent).setup()
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.store_webview)
        val client = webView.webViewClient

        assertFalse(client.shouldOverrideUrlLoading(webView, request("https://bandcamp.com/album")))
        assertTrue(client.shouldOverrideUrlLoading(webView, request("HTTP://qobuz.com/a?q=1#part")))
        assertEquals("https://qobuz.com/a?q=1#part", shadowOf(webView).lastLoadedUrl)
        assertFalse(client.shouldOverrideUrlLoading(webView, request("http://qobuz.com/frame", mainFrame = false)))
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

    @Test
    fun upgradesInitialUrlAndIntentFallbacks() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, MusicStoreWebViewActivity::class.java)
            .putExtra(MusicStoreWebViewActivity.EXTRA_URL, "http://qobuz.com/store")
        val controller = Robolectric.buildActivity(MusicStoreWebViewActivity::class.java, intent).setup()
        val webView = controller.get().findViewById<WebView>(R.id.store_webview)
        val client = webView.webViewClient
        assertEquals("https://qobuz.com/store", shadowOf(webView).lastLoadedUrl)

        assertTrue(client.shouldOverrideUrlLoading(webView, request(
            "intent://qobuz.com/store#Intent;scheme=http;end"
        )))
        assertEquals("https://qobuz.com/store", shadowOf(webView).lastLoadedUrl)
        shadowOf(context).checkActivities(true)
        assertTrue(client.shouldOverrideUrlLoading(webView, request(
            "intent://payment/#Intent;scheme=missingpaymentapp;package=missing.payment.app;" +
                "S.browser_fallback_url=http%3A%2F%2Fqobuz.com%2Fcheckout%3Fx%3D1;end"
        )))
        assertEquals("https://qobuz.com/checkout?x=1", shadowOf(webView).lastLoadedUrl)
        controller.destroy()
    }

    @Test
    fun retriesOnlyFailedHttpMainFrameGetsForCleartextErrors() {
        val message = "net::ERR_CLEARTEXT_NOT_PERMITTED"
        assertTrue(shouldRetryStoreUrl(request("http://qobuz.com/store"), WebViewClient.ERROR_UNKNOWN, message))
        assertTrue(shouldRetryStoreUrl(
            request("http://qobuz.com/unsafe"), WebViewClient.ERROR_UNSAFE_RESOURCE, "Unsafe resource"
        ))
        assertFalse(shouldRetryStoreUrl(
            request("http://qobuz.com/frame", mainFrame = false), WebViewClient.ERROR_UNKNOWN, message
        ))
        assertFalse(shouldRetryStoreUrl(
            request("http://qobuz.com/post", method = "POST"), WebViewClient.ERROR_UNKNOWN, message
        ))
        assertFalse(shouldRetryStoreUrl(request("https://qobuz.com/secure"), WebViewClient.ERROR_UNKNOWN, message))
        assertFalse(shouldRetryStoreUrl(
            request("http://qobuz.com/other"), WebViewClient.ERROR_HOST_LOOKUP, "Host lookup failed"
        ))
    }

    @Test
    fun removesWebViewMarkersWithoutChangingChromeVersion() {
        val webViewAgent = "Mozilla/5.0 (Linux; Android 16; Device; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/157.0.8103.50 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; Device) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/157.0.8103.50 Mobile Safari/537.36",
            mobileChromeUserAgent(webViewAgent)
        )
    }

    @Test
    fun refreshReloadsCurrentPageAndBrowserOpensStoreHome() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, MusicStoreWebViewActivity::class.java)
            .putExtra(MusicStoreWebViewActivity.EXTRA_URL, "https://qobuz.com/store?session=private")
        val controller = Robolectric.buildActivity(MusicStoreWebViewActivity::class.java, intent).setup()
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.store_webview)
        val menu = activity.findViewById<MaterialToolbar>(R.id.store_toolbar).menu

        assertTrue(menu.performIdentifierAction(R.id.store_refresh, 0))
        assertEquals(1, shadowOf(webView).reloadInvocations)
        assertTrue(menu.performIdentifierAction(R.id.store_open_browser, 0))
        val external = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, external.action)
        assertEquals("https://qobuz.com/", external.data.toString())
        assertTrue(external.hasCategory(Intent.CATEGORY_BROWSABLE))
        controller.destroy()
    }

    @Test
    fun cacheMissOffersSafeRecoveryWithoutReplayingFailedUrl() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, MusicStoreWebViewActivity::class.java)
            .putExtra(MusicStoreWebViewActivity.EXTRA_URL, "https://qobuz.com/store")
        val controller = Robolectric.buildActivity(MusicStoreWebViewActivity::class.java, intent).setup()
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.store_webview)

        activity.handleCacheMiss(false, "net::ERR_CACHE_MISS")
        activity.handleCacheMiss(true, "net::ERR_TIMED_OUT")
        assertEquals("https://qobuz.com/store", shadowOf(webView).lastLoadedUrl)
        activity.handleCacheMiss(true, "net::ERR_CACHE_MISS")
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("https://qobuz.com/", shadowOf(webView).lastLoadedUrl)

        activity.handleCacheMiss(true, "net::ERR_CACHE_MISS")
        val secondDialog = ShadowDialog.getLatestDialog() as AlertDialog
        secondDialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val external = shadowOf(activity).nextStartedActivity
        assertEquals("https://qobuz.com/", external.data.toString())
        controller.destroy()
    }

    private fun request(url: String, mainFrame: Boolean = true, method: String = "GET") = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = method
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
