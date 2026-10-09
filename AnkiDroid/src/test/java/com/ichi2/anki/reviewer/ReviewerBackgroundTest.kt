// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.Window
import androidx.appcompat.widget.ThemeUtils
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.cardviewer.AndroidCardRenderContext
import com.ichi2.anki.cardviewer.SingleCardSide
import com.ichi2.anki.cardviewer.TypeAnswer
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.previewer.stdHtml
import com.ichi2.anki.settings.enums.DayTheme
import com.ichi2.anki.settings.enums.NightTheme
import com.ichi2.anki.settings.enums.Theme
import com.ichi2.themes.Themes
import com.ichi2.utils.toRGBHex
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class ReviewerBackgroundTest : RobolectricTest() {
    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    @Test
    fun `official default follows every theme`() {
        val themes: List<Theme> = DayTheme.entries + NightTheme.entries
        for (theme in themes) {
            withTheme(theme) { context ->
                assertNull(ReviewerBackground.customColor(context))
                assertEquals(
                    ThemeUtils.getThemeAttrColor(context, R.attr.alternativeBackgroundColor),
                    ReviewerBackground.color(context, R.attr.alternativeBackgroundColor),
                )
            }
        }
    }

    @Test
    fun `modified background is black in both night themes`() {
        setMode(R.string.reviewer_background_custom_value)
        for (theme in NightTheme.entries) {
            withTheme(theme) { context ->
                assertEquals(Color.BLACK, ReviewerBackground.color(context, R.attr.alternativeBackgroundColor))
            }
        }
    }

    @Test
    fun `modified background is white in every day theme`() {
        setMode(R.string.reviewer_background_custom_value)
        for (theme in DayTheme.entries) {
            withTheme(theme) { context ->
                assertEquals(Color.WHITE, ReviewerBackground.color(context, R.attr.alternativeBackgroundColor))
            }
        }
    }

    @Test
    fun `saved selection survives recreating the themed context and switching back restores theme color`() {
        setMode(R.string.reviewer_background_custom_value)
        withTheme(NightTheme.DARK) { context ->
            assertEquals(Color.BLACK, ReviewerBackground.color(context, R.attr.alternativeBackgroundColor))
        }
        setMode(R.string.reviewer_background_official_value)
        withTheme(NightTheme.DARK) { context ->
            assertEquals(
                ThemeUtils.getThemeAttrColor(context, R.attr.alternativeBackgroundColor),
                ReviewerBackground.color(context, R.attr.alternativeBackgroundColor),
            )
        }
    }

    @Test
    fun `unknown saved selection falls back to official default`() {
        targetContext.sharedPrefs().edit { putString(targetContext.getString(R.string.reviewer_background_key), "unknown") }
        withTheme(NightTheme.DARK) { context ->
            assertEquals(
                ThemeUtils.getThemeAttrColor(context, android.R.attr.colorBackground),
                ReviewerBackground.color(context, android.R.attr.colorBackground),
            )
        }
    }

    @Test
    fun `applying a different selection updates both root and window`() {
        withTheme(NightTheme.DARK) { context ->
            val root = View(context)
            val window = mockk<Window>()
            val background = slot<ColorDrawable>()
            every { window.setBackgroundDrawable(capture(background)) } just Runs

            setMode(R.string.reviewer_background_custom_value)
            ReviewerBackground.apply(context, root, window, R.attr.alternativeBackgroundColor)
            assertEquals(Color.BLACK, (root.background as ColorDrawable).color)
            assertEquals(Color.BLACK, background.captured.color)

            setMode(R.string.reviewer_background_official_value)
            ReviewerBackground.apply(context, root, window, R.attr.alternativeBackgroundColor)
            assertEquals(
                ThemeUtils.getThemeAttrColor(context, R.attr.alternativeBackgroundColor),
                (root.background as ColorDrawable).color,
            )
            assertEquals(ThemeUtils.getThemeAttrColor(context, android.R.attr.colorBackground), background.captured.color)
        }
    }

    @Test
    fun `custom review HTML canvas changes while preview HTML keeps the official background`() {
        setMode(R.string.reviewer_background_custom_value)
        val themes: List<Theme> = DayTheme.entries + NightTheme.entries
        for (theme in themes) {
            withTheme(theme) { context ->
                val custom = ReviewerBackground.customColor(context)!!
                val reviewHtml = stdHtml(context, nightMode = Themes.isNightTheme, canvasColor = custom)
                val previewHtml = stdHtml(context, nightMode = Themes.isNightTheme)
                assertContains(reviewHtml, "--canvas: ${custom.toRGBHex()};")
                val official = ThemeUtils.getThemeAttrColor(context, android.R.attr.colorBackground).toRGBHex()
                assertContains(previewHtml, "--canvas: $official;")
            }
        }
    }

    @Test
    fun `legacy renderer isolates preview backgrounds and rereads review preferences on each card`() {
        val note = addBasicNote()
        val noteType = col.notetypes.get(note.noteTypeId)!!
        noteType.css = ".card { background-color: #123456; }"
        col.notetypes.save(noteType)
        val card = note.firstCard()
        withTheme(NightTheme.DARK) { context ->
            val typeAnswer = TypeAnswer.createInstance(context.sharedPrefs())
            val review = AndroidCardRenderContext.createInstance(context, col, typeAnswer, applyReviewerBackground = true)
            val preview = AndroidCardRenderContext.createInstance(context, col, typeAnswer)
            assertFalse(review.renderCard(col, card, SingleCardSide.FRONT).html.contains("--ankidroid-reviewer-canvas:"))

            setMode(R.string.reviewer_background_custom_value)
            val html = review.renderCard(col, card, SingleCardSide.FRONT).html
            assertContains(html, "--ankidroid-reviewer-canvas: #000000;")
            assertContains(html, "background-color: #123456;")
            assertFalse(html.contains("!important"))
            assertFalse(preview.renderCard(col, card, SingleCardSide.FRONT).html.contains("--ankidroid-reviewer-canvas:"))

            setMode(R.string.reviewer_background_official_value)
            assertFalse(review.renderCard(col, card, SingleCardSide.FRONT).html.contains("--ankidroid-reviewer-canvas:"))
            setMode(R.string.reviewer_background_custom_value)
            withTheme(DayTheme.LIGHT) {
                assertContains(review.renderCard(col, card, SingleCardSide.FRONT).html, "--ankidroid-reviewer-canvas: #FFFFFF;")
            }
        }
        val css =
            targetContext.assets
                .open("flashcard.css")
                .bufferedReader()
                .use { it.readText() }
        assertContains(css, "background-color: var(--ankidroid-reviewer-canvas, transparent);")
        assertContains(css, "background-color: var(--ankidroid-reviewer-canvas, black);")
        assertContains(css, "background-color: var(--ankidroid-reviewer-canvas, #303030);")
    }

    private fun setMode(valueResId: Int) {
        targetContext.sharedPrefs().edit {
            putString(targetContext.getString(R.string.reviewer_background_key), targetContext.getString(valueResId))
        }
    }

    private fun withTheme(
        theme: Theme,
        block: (Context) -> Unit,
    ) {
        val previousTheme = Themes.currentTheme
        Themes.currentTheme = theme
        try {
            block(ContextThemeWrapper(targetContext, theme.styleResId))
        } finally {
            Themes.currentTheme = previousTheme
        }
    }
}
