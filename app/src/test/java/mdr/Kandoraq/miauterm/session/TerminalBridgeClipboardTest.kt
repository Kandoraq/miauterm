package mdr.Kandoraq.miauterm.session

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalBridgeClipboardTest {
    @Test
    fun osc52IsDeniedByDefaultAndLegacyPreferenceDoesNotOptIn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().putBoolean("allow_osc52", true).commit()

        assertFalse(TerminalBridge.clipboardWritesAllowed(preferences))
    }

    @Test
    fun osc52RequiresTheNewExplicitOptIn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().putBoolean(TerminalBridge.PREF_ALLOW_CLIPBOARD, true).commit()

        assertTrue(TerminalBridge.clipboardWritesAllowed(preferences))
    }
}
