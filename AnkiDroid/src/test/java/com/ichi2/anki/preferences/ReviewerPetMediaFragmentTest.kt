// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.preferences

import android.graphics.Bitmap
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.commitNow
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.destinations.PreferencesDestination
import com.ichi2.anki.common.destinations.launchActivity
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.reviewer.pets.ReviewerPetMediaGroup
import com.ichi2.anki.reviewer.pets.ReviewerPetMediaStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReviewerPetMediaFragmentTest : RobolectricTest() {
    @Test
    fun `deleting a group from the list keeps original files and other groups`() {
        val source = image("first.png")
        val original = source.readBytes()
        val store = ReviewerPetMediaStore(targetContext)
        val first = assertNotNull(store.createGroup("First", listOf(Uri.fromFile(source))))
        val second = assertNotNull(store.createGroup("Second", listOf(Uri.fromFile(image("second.png")))))
        val copy = assertNotNull(store.mediaFile(first.id, first.files.single()))
        targetContext.sharedPrefs().edit { putBoolean(targetContext.getString(R.string.new_reviewer_options_key), true) }

        launchActivity<PreferencesActivity>(PreferencesDestination.Root).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = ReviewerPetMediaFragment()
                activity.supportFragmentManager.commitNow { replace(R.id.settings_container, fragment) }
                val firstDelete = deleteAction(fragment, first)
                assertTrue(deleteAction(fragment, second).isEnabled)

                firstDelete.performClick()
                val cancelDialog = getLatestAlertDialog()
                assertTrue(getAlertDialogText(false)!!.contains(first.name))
                cancelDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertEquals(listOf(first, second), store.groups())
                assertTrue(copy.isFile)

                firstDelete.performClick()
                getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                advanceRobolectricLooperUntil {
                    store.groups().none { it.id == first.id } && fragment.preferenceScreen.isEnabled
                }

                assertEquals(listOf(second), store.groups())
                assertEquals(second.id, store.enabledMedia().single().groupId)
                assertFalse(copy.exists())
                assertFalse(File(targetContext.filesDir, "reviewer-pets/${first.id}").exists())
                assertContentEquals(original, source.readBytes())
                assertEquals(1, categories(fragment).size)
                assertTrue(deleteAction(fragment, second).isEnabled)
            }
        }
    }

    @Test
    fun `empty disabled groups can be deleted and the list returns to its empty state`() {
        val store = ReviewerPetMediaStore(targetContext)
        val group = assertNotNull(store.createGroup("Empty", listOf(Uri.fromFile(image("empty.png")))))
        store.removeFile(group.id, group.files.single())
        targetContext.sharedPrefs().edit { putBoolean(targetContext.getString(R.string.new_reviewer_options_key), true) }

        launchActivity<PreferencesActivity>(PreferencesDestination.Root).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = ReviewerPetMediaFragment()
                activity.supportFragmentManager.commitNow { replace(R.id.settings_container, fragment) }
                val category = categories(fragment).single()
                val switch =
                    (0 until category.preferenceCount)
                        .map { category.getPreference(it) }
                        .filterIsInstance<SwitchPreferenceCompat>()
                        .single()
                assertFalse(switch.isEnabled)
                val delete = deleteAction(fragment, group)
                assertTrue(delete.isEnabled)
                delete.performClick()
                getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                advanceRobolectricLooperUntil { store.groups().isEmpty() && fragment.preferenceScreen.isEnabled }
                assertTrue(categories(fragment).isEmpty())
                assertTrue(
                    (0 until fragment.preferenceScreen.preferenceCount).any {
                        fragment.preferenceScreen.getPreference(it).title == targetContext.getString(R.string.reviewer_pet_empty)
                    },
                )
            }
        }
    }

    private fun deleteAction(
        fragment: ReviewerPetMediaFragment,
        group: ReviewerPetMediaGroup,
    ): Preference {
        val category = categories(fragment).first { it.getPreference(0).title == group.name }
        return assertNotNull(
            (0 until category.preferenceCount)
                .map { category.getPreference(it) }
                .firstOrNull { it.title == targetContext.getString(R.string.reviewer_pet_delete_group) },
            "Each media group must have a delete action in the list",
        )
    }

    private fun categories(fragment: ReviewerPetMediaFragment): List<PreferenceCategory> =
        (0 until fragment.preferenceScreen.preferenceCount)
            .map { fragment.preferenceScreen.getPreference(it) }
            .filterIsInstance<PreferenceCategory>()

    private fun getLatestAlertDialog(): AlertDialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)

    private fun image(name: String): File {
        val source = tempFolder.newFile(name)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return source
    }
}
