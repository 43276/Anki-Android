// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.Window
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.widget.ThemeUtils
import androidx.core.graphics.drawable.toDrawable
import com.ichi2.anki.R
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.themes.Themes
import com.ichi2.utils.toRGBHex
import org.json.JSONObject

/** Background of the review screen, independent of note type styling. */
object ReviewerBackground {
    @ColorInt
    fun color(
        context: Context,
        @AttrRes officialBackgroundAttr: Int,
    ): Int = customColor(context) ?: ThemeUtils.getThemeAttrColor(context, officialBackgroundAttr)

    @ColorInt
    fun customColor(context: Context): Int? {
        val value =
            context.sharedPrefs().getString(
                context.getString(R.string.reviewer_background_key),
                context.getString(R.string.reviewer_background_official_value),
            )
        return if (value == context.getString(R.string.reviewer_background_custom_value)) {
            if (Themes.isNightTheme) Color.BLACK else Color.WHITE
        } else {
            null
        }
    }

    fun apply(
        context: Context,
        root: View,
        window: Window,
        @AttrRes officialBackgroundAttr: Int = android.R.attr.colorBackground,
    ) {
        root.setBackgroundColor(color(context, officialBackgroundAttr))
        window.setBackgroundDrawable(color(context, android.R.attr.colorBackground).toDrawable())
    }

    /** Updates an already-rendered legacy card when returning from appearance settings. */
    fun legacyCanvasScript(context: Context): String {
        val canvas = customColor(context)?.toRGBHex()
        val css =
            if (canvas == null) {
                ":root { --ankidroid-reviewer-canvas: initial; background-color: transparent; }"
            } else {
                ":root { --ankidroid-reviewer-canvas: $canvas; background-color: $canvas; }"
            }
        return """
            (() => {
                let style = document.getElementById('ankidroid-reviewer-background');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'ankidroid-reviewer-background';
                    document.head?.appendChild(style);
                }
                style.textContent = ${JSONObject.quote(css)};
            })();
            """.trimIndent()
    }
}
