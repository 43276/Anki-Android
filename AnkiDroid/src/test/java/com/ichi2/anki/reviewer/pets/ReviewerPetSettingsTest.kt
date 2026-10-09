// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer.pets

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.testutils.EmptyApplication
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
class ReviewerPetSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val settings get() = ReviewerPetSettings(context)

    @Before
    fun setUp() {
        preferences.edit { clear() }
    }

    @Test
    fun `disabling the new study screen disables pet without discarding its settings`() {
        preferences.edit {
            putBoolean(context.getString(R.string.reviewer_pet_enabled_key), true)
            putInt(context.getString(R.string.reviewer_pet_size_key), 120)
        }
        assertFalse(settings.read().enabled)
        assertEquals(120, settings.read().sizeDp)
        preferences.edit { putBoolean(context.getString(R.string.new_reviewer_options_key), true) }
        assertTrue(settings.read().enabled)
        assertEquals(120, settings.read().sizeDp)
        preferences.edit { putBoolean(context.getString(R.string.reviewer_pet_enabled_key), false) }
        assertFalse(settings.read().enabled)
        assertEquals(120, settings.read().sizeDp)
    }

    @Test
    fun `all touch and animation settings are clamped when loaded`() {
        val bounds =
            listOf(
                Range(R.string.reviewer_pet_fly_probability_key, "flyProbabilityPercent", 0, 100),
                Range(R.string.reviewer_pet_fly_cooldown_key, "flyCooldownSeconds", 0, 3600),
                Range(R.string.reviewer_pet_reminder_seconds_key, "reminderSeconds", 5, 3600),
                Range(R.string.reviewer_pet_repeat_seconds_key, "repeatReminderSeconds", 5, 3600),
                Range(R.string.reviewer_pet_size_key, "sizeDp", 32, 256),
                Range(R.string.reviewer_pet_opacity_key, "opacityPercent", 25, 100),
                Range(R.string.reviewer_pet_move_duration_key, "moveDurationMs", 100, 10000),
                Range(R.string.reviewer_pet_edge_hide_key, "edgeHidePercent", 0, 85),
                Range(R.string.reviewer_pet_auto_expand_seconds_key, "autoExpandSeconds", 1, 3600),
                Range(R.string.reviewer_pet_fallback_size_key, "fallbackSizePercent", 25, 100),
                Range(R.string.reviewer_pet_avoidance_key, "avoidanceDp", 0, 64),
                Range(R.string.reviewer_pet_multi_click_key, "multiClickMs", 150, 600),
                Range(R.string.reviewer_pet_drag_sensitivity_key, "dragSensitivityPercent", 25, 200),
            )
        for (range in bounds) {
            preferences.edit { putInt(context.getString(range.key), Int.MIN_VALUE) }
            assertEquals(range.minimum, settings.read().toJson().getInt(range.field), range.field)
            preferences.edit { putInt(context.getString(range.key), Int.MAX_VALUE) }
            assertEquals(range.maximum, settings.read().toJson().getInt(range.field), range.field)
        }
    }

    @Test
    fun `unknown choices and malformed preference types use safe defaults`() {
        preferences.edit {
            putString(context.getString(R.string.reviewer_pet_movement_curve_key), "unexpected")
            putString(context.getString(R.string.reviewer_pet_edge_preference_key), "unexpected")
            putString(context.getString(R.string.reviewer_pet_multi_click_key), "wrong type")
        }
        assertEquals("ease-in-out", settings.read().movementCurve)
        assertEquals("nearest", settings.read().edgePreference)
        assertEquals(400, settings.read().multiClickMs)
    }

    private data class Range(
        val key: Int,
        val field: String,
        val minimum: Int,
        val maximum: Int,
    )
}
