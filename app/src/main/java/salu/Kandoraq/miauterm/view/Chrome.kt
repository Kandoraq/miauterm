package salu.Kandoraq.miauterm.view

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import kotlin.math.pow
import kotlin.math.roundToInt

object Chrome {

    class Palette(

        val bg: Int,
        val fg: Int,

        val bar: Int,

        val raised: Int,
        val raisedHigh: Int,
        val outline: Int,
        val text: Int,
        val textDim: Int,
        val textFaint: Int,
        val accent: Int,
        val accentStrong: Int,

        val accentSoft: Int,

        val onAccent: Int,
        val danger: Int,
        val dangerSoft: Int,

        val dark: Boolean
    )

    // Terminal color schemes affect terminal text only; app controls keep Miauterm's graphite palette.
    @Volatile
    var palette: Palette = graphitePalette()
        private set

    fun apply() {
        palette = graphitePalette()
    }

    private fun graphitePalette() = Palette(
        bg = rgb(0x0E, 0x10, 0x12),
        fg = rgb(0xFF, 0xFF, 0xFF),
        bar = rgb(0x09, 0x0B, 0x0D),
        raised = rgb(0x24, 0x29, 0x2E),
        raisedHigh = rgb(0x35, 0x3C, 0x43),
        outline = rgb(0x4B, 0x53, 0x5A),
        text = rgb(0xFF, 0xFF, 0xFF),
        textDim = rgb(0xD6, 0xDD, 0xE2),
        textFaint = rgb(0xA8, 0xB1, 0xB8),
        accent = rgb(0xFF, 0x8C, 0x32),
        accentStrong = rgb(0xA6, 0x51, 0x08),
        accentSoft = rgb(0x4E, 0x2A, 0x10),
        onAccent = rgb(0xFF, 0xFF, 0xFF),
        danger = rgb(0xFF, 0x6B, 0x6B),
        dangerSoft = rgb(0x3B, 0x20, 0x22),
        dark = true
    )

    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    private fun red(c: Int) = (c shr 16) and 0xff
    private fun green(c: Int) = (c shr 8) and 0xff
    private fun blue(c: Int) = c and 0xff

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun mix(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return rgb(
            (red(a) + (red(b) - red(a)) * f).roundToInt(),
            (green(a) + (green(b) - green(a)) * f).roundToInt(),
            (blue(a) + (blue(b) - blue(a)) * f).roundToInt()
        )
    }

    fun withAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0xffffff)

    private fun luminance(color: Int): Float {
        fun channel(value: Int): Float {
            val normalized = value / 255f
            return if (normalized <= 0.03928f) normalized / 12.92f else ((normalized + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
        return 0.2126f * channel(red(color)) + 0.7152f * channel(green(color)) + 0.0722f * channel(blue(color))
    }

    fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(color)
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun pressable(normal: Drawable, pressed: Drawable): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }

    fun flatPressable(normalColor: Int, pressedColor: Int): StateListDrawable =
        pressable(ColorDrawable(normalColor), ColorDrawable(pressedColor))

    fun ripple(content: Drawable, rippleColor: Int, radiusPx: Float): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, rounded(WHITE, radiusPx))
}
