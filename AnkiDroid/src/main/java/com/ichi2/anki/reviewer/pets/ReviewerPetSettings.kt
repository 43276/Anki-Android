// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer.pets

import android.content.Context
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.ichi2.anki.R
import org.json.JSONObject

/** A snapshot of validated settings; disabling the new study screen preserves the stored configuration. */
data class ReviewerPetConfig(
    val enabled: Boolean = false,
    val flyProbabilityPercent: Int = 10,
    val flyCooldownSeconds: Int = 30,
    val reminderEnabled: Boolean = true,
    val reminderSeconds: Int = 60,
    val repeatReminder: Boolean = false,
    val repeatReminderSeconds: Int = 120,
    val sizeDp: Int = 96,
    val opacityPercent: Int = 100,
    val moveDurationMs: Int = 900,
    val edgeHidePercent: Int = 70,
    val autoExpand: Boolean = true,
    val autoExpandSeconds: Int = 60,
    val edgePreference: String = "nearest",
    val fallbackSizePercent: Int = 60,
    val avoidanceDp: Int = 8,
    val movementCurve: String = "ease-in-out",
    val multiClickMs: Int = 400,
    val dragSensitivityPercent: Int = 100,
) {
    fun toJson(): JSONObject =
        JSONObject().apply {
            put("enabled", enabled)
            put("flyProbabilityPercent", flyProbabilityPercent)
            put("flyCooldownSeconds", flyCooldownSeconds)
            put("reminderEnabled", reminderEnabled)
            put("reminderSeconds", reminderSeconds)
            put("repeatReminder", repeatReminder)
            put("repeatReminderSeconds", repeatReminderSeconds)
            put("sizeDp", sizeDp)
            put("opacityPercent", opacityPercent)
            put("moveDurationMs", moveDurationMs)
            put("edgeHidePercent", edgeHidePercent)
            put("autoExpand", autoExpand)
            put("autoExpandSeconds", autoExpandSeconds)
            put("edgePreference", edgePreference)
            put("fallbackSizePercent", fallbackSizePercent)
            put("avoidanceDp", avoidanceDp)
            put("movementCurve", movementCurve)
            put("multiClickMs", multiClickMs)
            put("dragSensitivityPercent", dragSensitivityPercent)
        }
}

class ReviewerPetSettings(
    private val context: Context,
) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    fun read(): ReviewerPetConfig =
        ReviewerPetConfig(
            enabled = boolean(R.string.new_reviewer_options_key, false) && boolean(R.string.reviewer_pet_enabled_key, false),
            flyProbabilityPercent = number(R.string.reviewer_pet_fly_probability_key, 10, 0, 100),
            flyCooldownSeconds = number(R.string.reviewer_pet_fly_cooldown_key, 30, 0, 3600),
            reminderEnabled = boolean(R.string.reviewer_pet_reminder_enabled_key, true),
            reminderSeconds = number(R.string.reviewer_pet_reminder_seconds_key, 60, 5, 3600),
            repeatReminder = boolean(R.string.reviewer_pet_repeat_reminder_key, false),
            repeatReminderSeconds = number(R.string.reviewer_pet_repeat_seconds_key, 120, 5, 3600),
            sizeDp = number(R.string.reviewer_pet_size_key, 96, 32, 256),
            opacityPercent = number(R.string.reviewer_pet_opacity_key, 100, 25, 100),
            moveDurationMs = number(R.string.reviewer_pet_move_duration_key, 900, 100, 10000),
            edgeHidePercent = number(R.string.reviewer_pet_edge_hide_key, 70, 0, 85),
            autoExpand = boolean(R.string.reviewer_pet_auto_expand_key, true),
            autoExpandSeconds = number(R.string.reviewer_pet_auto_expand_seconds_key, 60, 1, 3600),
            edgePreference = choice(R.string.reviewer_pet_edge_preference_key, "nearest", setOf("nearest", "left", "right")),
            fallbackSizePercent = number(R.string.reviewer_pet_fallback_size_key, 60, 25, 100),
            avoidanceDp = number(R.string.reviewer_pet_avoidance_key, 8, 0, 64),
            movementCurve = choice(R.string.reviewer_pet_movement_curve_key, "ease-in-out", setOf("linear", "ease-in-out", "ease-out")),
            multiClickMs = number(R.string.reviewer_pet_multi_click_key, 400, 150, 600),
            dragSensitivityPercent = number(R.string.reviewer_pet_drag_sensitivity_key, 100, 25, 200),
        )

    private fun boolean(
        @StringRes key: Int,
        fallback: Boolean,
    ): Boolean = runCatching { preferences.getBoolean(context.getString(key), fallback) }.getOrDefault(fallback)

    private fun number(
        @StringRes key: Int,
        fallback: Int,
        minimum: Int,
        maximum: Int,
    ): Int = runCatching { preferences.getInt(context.getString(key), fallback) }.getOrDefault(fallback).coerceIn(minimum, maximum)

    private fun choice(
        @StringRes key: Int,
        fallback: String,
        allowed: Set<String>,
    ): String =
        runCatching { preferences.getString(context.getString(key), fallback) }.getOrDefault(fallback)?.takeIf { it in allowed } ?: fallback
}
