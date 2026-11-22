package salu.Kandoraq.miauterm.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.appcompat.app.AppCompatActivity
import salu.Kandoraq.Miauterm.R
import org.xmlpull.v1.XmlPullParser
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChromeTest {

    @Test
    fun mixGoesFromOneColorToTheOther() {
        val color = Chrome.rgb(0x11, 0x22, 0x33)
        assertEquals(color, Chrome.mix(color, Chrome.WHITE, 0f))
        assertEquals(Chrome.WHITE, Chrome.mix(color, Chrome.WHITE, 1f))
        assertEquals(Chrome.rgb(0x80, 0x80, 0x80), Chrome.mix(Chrome.BLACK, Chrome.WHITE, 0.5f))
    }

    @Test
    fun fixedChromePaletteKeepsPrimaryDialogTextReadable() {
        Chrome.apply()
        val palette = Chrome.palette
        assertTrue(Chrome.contrast(palette.onAccent, palette.accentStrong) >= 4.5f)
        assertTrue(Chrome.contrast(palette.text, palette.bg) >= 4.5f)
        assertTrue(palette.dark)
    }

    @Test
    fun terminalThemePreferenceIsAbsentFromTheSettingsResource() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val parser = activity.resources.getXml(R.xml.root_preferences)
        var hasThemePreference = false
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG &&
                    parser.getAttributeValue("http://schemas.android.com/apk/res-auto", "key") == "terminal_theme"
                ) {
                    hasThemePreference = true
                }
            }
        } finally {
            parser.close()
        }
        assertFalse(hasThemePreference)
    }
}
