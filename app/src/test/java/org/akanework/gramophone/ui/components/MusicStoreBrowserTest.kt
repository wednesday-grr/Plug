package org.akanework.gramophone.ui.components

import android.app.Activity
import android.app.Application
import android.content.Intent
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
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MusicStoreBrowserTest {
    @Test
    fun opensStoresInCustomTabs() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        for (url in listOf(
            "https://qobuz.com", "https://bandcamp.com", "https://7digital.com", "https://ototoy.jp"
        )) {
            assertTrue(openMusicStoreInBrowser(activity, url))
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.data.toString())
            assertTrue(intent.hasExtra("android.support.customtabs.extra.SESSION"))
        }
        controller.destroy()
    }

    @Test
    fun reportsWhenNoBrowserIsAvailable() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        assertFalse(openMusicStoreInBrowser(activity, "https://qobuz.com"))
        assertEquals(
            activity.getString(org.akanework.gramophone.R.string.music_store_no_browser),
            ShadowToast.getTextOfLatestToast()
        )
        controller.destroy()
    }
}
