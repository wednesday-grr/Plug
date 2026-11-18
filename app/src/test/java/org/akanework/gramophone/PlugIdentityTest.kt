/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import org.akanework.gramophone.logic.PlugAlbumArtProvider
import org.akanework.gramophone.logic.PlugPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PlugIdentityTest {
    @Test
    fun manifestComponentsAndSearchUseVariantApplicationId() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        val service = pm.getServiceInfo(ComponentName(context, PlugPlaybackService::class.java), 0)
        assertEquals(PlugPlaybackService::class.java.name, service.name)
        val provider = pm.resolveContentProvider("${context.packageName}.albumart", 0)
        assertEquals(PlugAlbumArtProvider::class.java.name, provider!!.name)
        assertEquals(PlugAlbumArtProvider.PROVIDER_AUTHORITY, provider.authority)
        assertEquals(context.packageName, pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).packageName)

        context.resources.getXml(R.xml.searchable).use { xml ->
            while (xml.next() != XmlPullParser.START_TAG) Unit
            val android = "http://schemas.android.com/apk/res/android"
            assertEquals("${context.packageName}.search", xml.getAttributeValue(android, "searchSuggestAuthority"))
            assertEquals("${context.packageName}.action.PLAY_MEDIA_FROM_SUGGESTION",
                xml.getAttributeValue(android, "searchSuggestIntentAction"))
        }
    }
}
