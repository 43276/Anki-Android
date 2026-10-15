// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.preferences

import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.view.descendants
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReviewerPetMediaFragmentTest : RobolectricTest() {
    @Test
    fun `opening a media group displays its files and survives recreation`() {
        val store = ReviewerPetMediaStore(targetContext)
        val group =
            assertNotNull(
                store.createGroup("Pet", listOf(Uri.fromFile(image("one.png")), Uri.fromFile(image("two.png")))),
            )
        assertNotNull(store.createGroup("Other", listOf(Uri.fromFile(image("other.png")))))
        enablePetSettings()

        launchActivity<PreferencesActivity>(PreferencesDestination.Root).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = openGroup(openMediaList(activity), group)
                assertGroupFiles(fragment, group)

                assertNotNull(fragment.findPreference<Preference>("petMediaPreview:${group.files.first()}")).performClick()
                val preview = getLatestAlertDialog()
                assertTrue(preview.isShowing)
                assertEquals(1, (preview.window!!.decorView as ViewGroup).descendants.filterIsInstance<WebView>().count())
                preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertEquals(group.files, store.groups().first().files)
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                val preferences = assertIs<PreferencesFragment>(activity.fragment)
                val fragment =
                    assertIs<ReviewerPetGroupFragment>(preferences.childFragmentManager.findFragmentById(R.id.settings_container))
                assertGroupFiles(fragment, group)
            }
        }
    }

    @Test
    fun `recreating after the last file was deleted returns to the media list`() {
        val store = ReviewerPetMediaStore(targetContext)
        val group = assertNotNull(store.createGroup("Pet", listOf(Uri.fromFile(image("last.png")))))
        enablePetSettings()

        launchActivity<PreferencesActivity>(PreferencesDestination.Root).use { scenario ->
            scenario.onActivity { activity ->
                assertGroupFiles(openGroup(openMediaList(activity), group), group)
            }

            scenario.moveToState(Lifecycle.State.CREATED)
            // Simulate deletion reaching disk while the old screen can no longer handle its completion callback.
            store.removeFile(group.id, group.files.single())
            assertTrue(store.groups().isEmpty())
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.RESUMED)

            scenario.onActivity { activity ->
                val preferences = assertIs<PreferencesFragment>(activity.fragment)
                val manager = preferences.childFragmentManager
                advanceRobolectricLooperUntil {
                    manager.findFragmentById(R.id.settings_container) is ReviewerPetMediaFragment
                }
                val mediaList = assertIs<ReviewerPetMediaFragment>(manager.findFragmentById(R.id.settings_container))
                assertTrue(mediaList.preferenceScreen.isEnabled)
                assertTrue(categories(mediaList).isEmpty())
                assertTrue(
                    (0 until mediaList.preferenceScreen.preferenceCount).any {
                        mediaList.preferenceScreen.getPreference(it).title == targetContext.getString(R.string.reviewer_pet_empty)
                    },
                )
                assertEquals(0, manager.backStackEntryCount)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun `individual deletion keeps remaining media and confirms removal of the last media group`() {
        val source = image("first.png")
        val sourceBytes = source.readBytes()
        val secondSource = image("second.png")
        val secondBytes = secondSource.readBytes()
        val store = ReviewerPetMediaStore(targetContext)
        val group = assertNotNull(store.createGroup("Pet", listOf(Uri.fromFile(source), Uri.fromFile(secondSource))))
        val other = assertNotNull(store.createGroup("Other", listOf(Uri.fromFile(image("other.png")))))
        val firstFile = group.files.first()
        val lastFile = group.files.last()
        val firstCopy = assertNotNull(store.mediaFile(group.id, firstFile))
        val lastCopy = assertNotNull(store.mediaFile(group.id, lastFile))
        enablePetSettings()

        launchActivity<PreferencesActivity>(PreferencesDestination.Root).use { scenario ->
            scenario.onActivity { activity ->
                val mediaList = openMediaList(activity)
                val fragment = openGroup(mediaList, group)
                val removeFirst = assertNotNull(fragment.findPreference<Preference>("petMediaRemove:$firstFile"))
                removeFirst.performClick()
                assertEquals(targetContext.getString(R.string.reviewer_pet_remove_file_confirm), getAlertDialogText(false))
                getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertEquals(listOf(group, other), store.groups())
                assertTrue(firstCopy.isFile)

                removeFirst.performClick()
                getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                advanceRobolectricLooperUntil {
                    store.groups().first().files == listOf(lastFile) && fragment.preferenceScreen.isEnabled
                }
                val remaining = group.copy(files = listOf(lastFile))
                assertEquals(listOf(remaining, other), store.groups())
                assertGroupFiles(fragment, remaining)
                assertNull(fragment.findPreference<Preference>("petMediaRemove:$firstFile"))
                assertFalse(firstCopy.exists())
                assertTrue(lastCopy.isFile)

                val removeLast = assertNotNull(fragment.findPreference<Preference>("petMediaRemove:$lastFile"))
                removeLast.performClick()
                assertEquals(
                    targetContext.getString(R.string.reviewer_pet_remove_last_file_confirm, group.name),
                    getAlertDialogText(false),
                )
                getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertEquals(listOf(remaining, other), store.groups())
                assertTrue(lastCopy.isFile)

                removeLast.performClick()
                getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                val preferences = assertIs<PreferencesFragment>(activity.fragment)
                advanceRobolectricLooperUntil {
                    store.groups() == listOf(other) &&
                        preferences.childFragmentManager.findFragmentById(R.id.settings_container) === mediaList &&
                        mediaList.preferenceScreen.isEnabled
                }
                assertEquals(listOf(other), ReviewerPetMediaStore(targetContext).groups())
                assertFalse(lastCopy.exists())
                assertFalse(File(targetContext.filesDir, "reviewer-pets/${group.id}").exists())
                assertContentEquals(sourceBytes, source.readBytes())
                assertContentEquals(secondBytes, secondSource.readBytes())
                assertEquals(1, categories(mediaList).size)
                assertTrue(deleteAction(mediaList, other).isEnabled)
                assertFalse(activity.isFinishing)
            }
        }
    }

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
        assertTrue(assertNotNull(store.mediaFile(group.id, group.files.single())).delete())
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

    private fun enablePetSettings() {
        targetContext.sharedPrefs().edit { putBoolean(targetContext.getString(R.string.new_reviewer_options_key), true) }
    }

    private fun openMediaList(activity: PreferencesActivity): ReviewerPetMediaFragment {
        val preferences = assertIs<PreferencesFragment>(activity.fragment)
        val fragment = ReviewerPetMediaFragment()
        preferences.childFragmentManager.commitNow { replace(R.id.settings_container, fragment) }
        return fragment
    }

    private fun openGroup(
        fragment: ReviewerPetMediaFragment,
        group: ReviewerPetMediaGroup,
    ): ReviewerPetGroupFragment {
        val manager = fragment.parentFragmentManager
        categories(fragment).first { it.getPreference(0).title == group.name }.getPreference(0).performClick()
        manager.executePendingTransactions()
        return assertIs<ReviewerPetGroupFragment>(manager.findFragmentById(R.id.settings_container))
    }

    private fun assertGroupFiles(
        fragment: ReviewerPetGroupFragment,
        group: ReviewerPetMediaGroup,
    ) {
        assertEquals(group.id, fragment.requireArguments().getString(ReviewerPetGroupFragment.GROUP_ID))
        assertEquals(group.name, fragment.preferenceScreen.title)
        assertEquals(
            group.files.size,
            (0 until fragment.preferenceScreen.preferenceCount)
                .map { fragment.preferenceScreen.getPreference(it) }
                .filterIsInstance<PreferenceCategory>()
                .size,
        )
        group.files.forEachIndexed { index, fileName ->
            val category = assertNotNull(fragment.findPreference<PreferenceCategory>("petMediaFile:$fileName"))
            val preview = assertNotNull(category.findPreference<Preference>("petMediaPreview:$fileName"))
            assertEquals(targetContext.getString(R.string.reviewer_pet_file, index + 1), preview.title)
            assertEquals("PNG", preview.summary)
            assertTrue(preview.isEnabled)
            val remove = assertNotNull(category.findPreference<Preference>("petMediaRemove:$fileName"))
            assertEquals(targetContext.getString(R.string.reviewer_pet_remove_file), remove.title)
            assertTrue(remove.isEnabled)
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
