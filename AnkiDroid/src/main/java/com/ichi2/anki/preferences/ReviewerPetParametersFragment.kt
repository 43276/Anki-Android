// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.preferences

import android.content.SharedPreferences
import androidx.preference.Preference
import com.ichi2.anki.R
import com.ichi2.preferences.NumberRangePreferenceCompat

class ReviewerPetParametersFragment : SettingsFragment() {
    override val preferenceResource: Int = R.xml.preferences_reviewer_pet_parameters
    override val analyticsScreenNameConstant: String = "prefs.studyScreen.pet.parameters"

    override fun initSubscreen() {
        configureNumber(R.string.reviewer_pet_fly_probability_key, 0, 100, R.string.reviewer_pet_unit_percent)
        configureNumber(R.string.reviewer_pet_fly_cooldown_key, 0, 3600, R.string.reviewer_pet_unit_seconds)
        configureNumber(R.string.reviewer_pet_reminder_seconds_key, 5, 3600, R.string.reviewer_pet_unit_seconds)
        configureNumber(R.string.reviewer_pet_repeat_seconds_key, 5, 3600, R.string.reviewer_pet_unit_seconds)
        configureNumber(R.string.reviewer_pet_size_key, 32, 256, R.string.reviewer_pet_unit_dp)
        configureNumber(R.string.reviewer_pet_opacity_key, 25, 100, R.string.reviewer_pet_unit_percent)
        configureNumber(R.string.reviewer_pet_move_duration_key, 100, 10000, R.string.reviewer_pet_unit_ms)
        configureNumber(R.string.reviewer_pet_edge_hide_key, 0, 85, R.string.reviewer_pet_unit_percent)
        configureNumber(R.string.reviewer_pet_auto_expand_seconds_key, 1, 3600, R.string.reviewer_pet_unit_seconds)
        configureNumber(R.string.reviewer_pet_fallback_size_key, 25, 100, R.string.reviewer_pet_unit_percent)
        configureNumber(R.string.reviewer_pet_avoidance_key, 0, 64, R.string.reviewer_pet_unit_dp)
        configureNumber(R.string.reviewer_pet_multi_click_key, 150, 600, R.string.reviewer_pet_unit_ms)
        configureNumber(R.string.reviewer_pet_drag_sensitivity_key, 25, 200, R.string.reviewer_pet_unit_percent)
        updateEnabled()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?,
    ) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        if (key == getString(R.string.new_reviewer_options_key)) updateEnabled()
    }

    private fun updateEnabled() {
        preferenceScreen.isEnabled =
            preferenceManager.sharedPreferences?.getBoolean(getString(R.string.new_reviewer_options_key), false) == true
    }

    private fun configureNumber(
        key: Int,
        minimum: Int,
        maximum: Int,
        unit: Int,
    ) {
        requirePreference<NumberRangePreferenceCompat>(key).summaryProvider =
            Preference.SummaryProvider<NumberRangePreferenceCompat> { preference ->
                "${preference.getValue()} ${getString(unit)} · ${getString(R.string.reviewer_pet_range, minimum, maximum, getString(unit))}"
            }
    }
}
