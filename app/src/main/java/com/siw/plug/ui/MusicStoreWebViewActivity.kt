/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.ui

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.siw.plug.R
import com.siw.plug.logic.enableEdgeToEdgeProperly
import com.siw.plug.logic.hasAudioPermission
import com.siw.plug.logic.plugApplication
import com.siw.plug.logic.ui.BaseActivity
import com.siw.plug.logic.utils.DownloadScanner
import com.siw.plug.logic.utils.MusicStoreDownloadReceiver
import com.siw.plug.logic.utils.MusicStoreDownloads
import java.net.URISyntaxException
import java.util.Locale
import java.util.UUID

internal fun upgradeStoreUrl(uri: Uri): Uri =
    if (uri.scheme.equals("http", true)) uri.buildUpon().scheme("https").build() else uri

internal fun mobileChromeUserAgent(userAgent: String): String =
    userAgent.replace("; wv", "").replace("Version/4.0 ", "")

internal fun shouldRetryStoreUrl(
    request: WebResourceRequest, errorCode: Int, description: CharSequence
): Boolean = request.isForMainFrame && request.method.equals("GET", true) &&
    request.url.scheme.equals("http", true) &&
    (errorCode == WebViewClient.ERROR_UNSAFE_RESOURCE ||
        description.contains("ERR_CLEARTEXT_NOT_PERMITTED", true))

class MusicStoreWebViewActivity : BaseActivity() {
    companion object {
        const val EXTRA_URL = "store_url"
        const val EXTRA_TITLE = "store_title"
        private const val PENDING_DOWNLOAD = "pending_download"
    }

    private lateinit var webView: WebView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private var pendingDownload: ArrayList<String>? = null
    private var scanning = false
    private var cacheErrorDialogShowing = false
    private var storeHome: Uri? = null

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val download = pendingDownload
        pendingDownload = null
        if (granted && download != null) {
            enqueueDownload(download)
        } else if (download != null) {
            toast(R.string.music_store_download_permission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeProperly()
        setContentView(R.layout.activity_music_store_webview)
        val root = findViewById<View>(R.id.store_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        toolbar = findViewById(R.id.store_toolbar)
        progress = findViewById(R.id.store_progress)
        webView = findViewById(R.id.store_webview)
        pendingDownload = savedInstanceState?.getStringArrayList(PENDING_DOWNLOAD)
        toolbar.title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.music_store)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.store_back -> webView.goBack()
                R.id.store_forward -> webView.goForward()
                R.id.store_refresh -> webView.reload()
                R.id.store_open_browser -> openStoreInBrowser()
                R.id.store_scan_action -> scanDownloads()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.userAgentString = mobileChromeUserAgent(webView.settings.userAgentString)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                if (uri.scheme.equals("http", true)) {
                    view.loadUrl(upgradeStoreUrl(uri).toString())
                    return true
                }
                if (uri.scheme.equals("https", true)) return false
                openExternal(uri)
                return true
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (shouldRetryStoreUrl(request, error.errorCode, error.description)) {
                    view.loadUrl(upgradeStoreUrl(request.url).toString())
                } else {
                    handleCacheMiss(request.isForMainFrame, error.description)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                updateNavigation()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                updateNavigation()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progress.setProgressCompat(newProgress, true)
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val download = arrayListOf(url, userAgent ?: "", contentDisposition ?: "", mimeType ?: "")
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownload = download
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                enqueueDownload(download)
            }
        }
        val uri = intent.getStringExtra(EXTRA_URL)?.let(Uri::parse)
        if (uri == null || uri.host.isNullOrEmpty() ||
            !(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))
        ) {
            toast(R.string.music_store_external_unavailable)
            finish()
            return
        }
        val secureUri = upgradeStoreUrl(uri)
        storeHome = secureUri.buildUpon().path("/").clearQuery().fragment(null).build()
        val restored = savedInstanceState?.let { webView.restoreState(it) }
        if (restored == null) {
            webView.loadUrl(secureUri.toString())
        }
        updateNavigation()
    }

    internal fun handleCacheMiss(isMainFrame: Boolean, description: CharSequence) {
        if (!isMainFrame || !description.contains("ERR_CACHE_MISS", true) ||
            cacheErrorDialogShowing || storeHome == null || isFinishing
        ) return
        cacheErrorDialogShowing = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.music_store_cache_error_title)
            .setMessage(R.string.music_store_cache_error_message)
            .setPositiveButton(R.string.music_store_return_to_store) { _, _ ->
                webView.loadUrl(storeHome.toString())
            }
            .setNeutralButton(R.string.music_store_open_browser) { _, _ ->
                openStoreInBrowser()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { cacheErrorDialogShowing = false }
            .show()
    }

    private fun openStoreInBrowser() {
        val home = storeHome ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, home).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Log.w("MusicStore", "No browser available", e)
            toast(R.string.music_store_no_browser)
        } catch (e: SecurityException) {
            Log.w("MusicStore", "Browser rejected store link", e)
            toast(R.string.music_store_no_browser)
        }
    }

    private fun updateNavigation() {
        toolbar.menu.findItem(R.id.store_back).isEnabled = webView.canGoBack()
        toolbar.menu.findItem(R.id.store_forward).isEnabled = webView.canGoForward()
    }

    private fun openExternal(uri: Uri) {
        var fallback: String? = null
        var targetPackage: String? = null
        val target = try {
            if (uri.scheme.equals("intent", true)) {
                val parsed = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                fallback = parsed.getStringExtra("browser_fallback_url")
                targetPackage = parsed.`package`
                parsed.data
            } else uri
        } catch (e: URISyntaxException) {
            Log.w("MusicStore", "Invalid external link", e)
            null
        }
        val safeTarget = target?.let(::upgradeStoreUrl)
        val scheme = safeTarget?.scheme?.lowercase(Locale.ROOT)
        if (target != null && scheme in setOf("http", "https") && targetPackage == null) {
            webView.loadUrl(safeTarget.toString())
            return
        }
        if (target != null && scheme != null &&
            scheme !in setOf("file", "content", "javascript", "intent")
        ) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, safeTarget).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    `package` = targetPackage
                })
                return
            } catch (e: ActivityNotFoundException) {
                Log.w("MusicStore", "No handler for external link", e)
            } catch (e: SecurityException) {
                Log.w("MusicStore", "External link was rejected", e)
            }
        }
        val fallbackUri = fallback?.let(Uri::parse)
        if (fallbackUri != null &&
            (fallbackUri.scheme.equals("http", true) || fallbackUri.scheme.equals("https", true))
        ) {
            webView.loadUrl(upgradeStoreUrl(fallbackUri).toString())
        } else if (target != null && scheme in setOf("http", "https")) {
            webView.loadUrl(safeTarget.toString())
        } else {
            toast(R.string.music_store_external_unavailable)
        }
    }

    private fun enqueueDownload(download: List<String>) {
        val (url, userAgent, disposition, mimeType) = download
        val uri = Uri.parse(url)
        if (!(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))) {
            toast(R.string.music_store_download_unsupported)
            return
        }
        val guessed = URLUtil.guessFileName(url, disposition, mimeType)
        if (!MusicStoreDownloads.isSupported(guessed, mimeType)) {
            toast(R.string.music_store_download_unsupported)
            return
        }
        val filename = MusicStoreDownloads.destinationName(guessed, UUID.randomUUID().toString().take(8))
        try {
            val request = DownloadManager.Request(uri)
                .setTitle(guessed)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            if (mimeType.isNotBlank()) request.setMimeType(mimeType)
            if (userAgent.isNotBlank()) request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            MusicStoreDownloadReceiver.track(this, id, filename, MusicStoreDownloads.isArchive(guessed, mimeType))
            toast(R.string.music_store_download_started)
        } catch (e: IllegalArgumentException) {
            Log.w("MusicStore", "Could not enqueue download", e)
            toast(R.string.music_store_download_failed)
        } catch (e: SecurityException) {
            Log.w("MusicStore", "Could not enqueue download", e)
            toast(R.string.music_store_download_failed)
        } catch (e: IllegalStateException) {
            Log.w("MusicStore", "Could not enqueue download", e)
            toast(R.string.music_store_download_failed)
        }
    }

    private fun scanDownloads() {
        if (scanning) return
        if (!hasAudioPermission()) {
            toast(R.string.music_store_scan_permission)
            return
        }
        scanning = true
        toolbar.menu.findItem(R.id.store_scan_action).isEnabled = false
        toast(R.string.music_store_scanning)
        lifecycleScope.launch {
            val complete = withContext(Dispatchers.IO) { DownloadScanner.scan(applicationContext) }
            plugApplication.reader.refresh()
            scanning = false
            toolbar.menu.findItem(R.id.store_scan_action).isEnabled = true
            toast(if (complete) R.string.music_store_scan_complete else R.string.music_store_scan_partial)
        }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        pendingDownload?.let { outState.putStringArrayList(PENDING_DOWNLOAD, it) }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }
}
