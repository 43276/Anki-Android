// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer.pets

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.testutils.EmptyApplication
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
class ReviewerPetMediaStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var store: ReviewerPetMediaStore

    @Before
    fun setUp() {
        val files = temporaryFolder.newFolder("private")
        context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
                override fun getFilesDir(): File = files
            }
        store = ReviewerPetMediaStore(context)
    }

    @Test
    fun `empty and failed imports leave no enabled media group`() {
        assertTrue(store.groups().isEmpty())
        assertNull(store.createGroup("cancelled", emptyList()))
        assertNull(store.createGroup(" ", listOf(image("blank.png"))))
        val bad = temporaryFolder.newFile("bad.png").apply { writeText("not an image") }
        assertNull(store.createGroup("failed", listOf(Uri.fromFile(bad))))
        assertNull(store.createGroup("missing", listOf(Uri.fromFile(File(temporaryFolder.root, "missing.png")))))
        assertTrue(store.groups().isEmpty())
        assertTrue(store.enabledMedia().isEmpty())
    }

    @Test
    fun `imports are private copies and persist name enabled and file arrays`() {
        val source = image("pet.png")
        val sourceFile = File(source.path!!)
        val original = sourceFile.readBytes()
        val group = assertNotNull(store.createGroup("My pet", listOf(source)))
        sourceFile.delete()
        val media = store.enabledMedia().single()
        assertTrue(
            assertNotNull(
                store.mediaFile(group.id, media.fileName),
            ).absolutePath.startsWith(File(context.filesDir, "reviewer-pets").absolutePath),
        )
        assertContentEquals(original, assertNotNull(store.intercept(Uri.parse(media.url))).data.use { it.readBytes() })
        val persisted = JSONArray(File(context.filesDir, "reviewer-pets/groups.json").readText()).getJSONObject(0)
        assertEquals("My pet", persisted.getString("name"))
        assertTrue(persisted.getBoolean("enabled"))
        assertEquals(1, persisted.getJSONArray("files").length())
        assertEquals(listOf(group), ReviewerPetMediaStore(context).groups())
        store.renameGroup(group.id, "Renamed")
        store.setEnabled(group.id, false)
        assertEquals("Renamed", ReviewerPetMediaStore(context).groups().single().name)
        assertTrue(ReviewerPetMediaStore(context).enabledMedia().isEmpty())
    }

    @Test
    fun `enabled files form a flat unweighted pool`() {
        val first = assertNotNull(store.createGroup("First", listOf(image("one.png"), image("two.png"))))
        val second = assertNotNull(store.createGroup("Second", listOf(image("three.png"))))
        assertEquals(3, store.enabledMedia().size)
        assertEquals(2, store.enabledMedia().count { it.groupId == first.id })
        assertEquals(1, store.enabledMedia().count { it.groupId == second.id })
        store.setEnabled(second.id, false)
        assertEquals(2, store.enabledMedia().size)
        assertEquals(1, store.addFiles(second.id, listOf(image("four.png"))))
        assertFalse(store.groups().first { it.id == second.id }.enabled)
        store.setEnabled(second.id, true)
        assertEquals(4, store.enabledMedia().size)
        store.deleteGroup(first.id)
        assertEquals(2, store.enabledMedia().size)
        assertFalse(File(context.filesDir, "reviewer-pets/${first.id}").exists())
    }

    @Test
    fun `deleting individual media removes only copies and removes the group after the last file`() {
        val sources = listOf(image("first.png"), image("second.png"))
        val originals = sources.map { File(it.path!!) }
        val originalBytes = originals.map { it.readBytes() }
        val group = assertNotNull(store.createGroup("Pet", sources))
        val other = assertNotNull(store.createGroup("Other", listOf(image("other.png"))))
        val copies = group.files.map { assertNotNull(store.mediaFile(group.id, it)) }
        val otherCopy = assertNotNull(store.mediaFile(other.id, other.files.single()))
        val otherBytes = otherCopy.readBytes()
        val directory = copies.first().parentFile!!

        store.removeFile(group.id, group.files.first())

        assertFalse(copies.first().exists())
        assertTrue(copies.last().isFile)
        assertTrue(directory.isDirectory)
        assertEquals(listOf(group.copy(files = listOf(group.files.last())), other), ReviewerPetMediaStore(context).groups())

        store.removeFile(group.id, group.files.last())

        assertFalse(directory.exists())
        assertEquals(listOf(other), ReviewerPetMediaStore(context).groups())
        assertEquals(listOf(other.id), store.enabledMedia().map { it.groupId })
        assertContentEquals(otherBytes, otherCopy.readBytes())
        originals.zip(originalBytes).forEach { (original, bytes) -> assertContentEquals(bytes, original.readBytes()) }
    }

    @Test
    fun `deleting an unmatched media file leaves group files and manifest unchanged`() {
        val group = assertNotNull(store.createGroup("Pet", listOf(image("listed.png"))))
        val other = assertNotNull(store.createGroup("Other", listOf(image("another.png"))))
        val copy = assertNotNull(store.mediaFile(group.id, group.files.single()))
        val manifest = File(context.filesDir, "reviewer-pets/groups.json")
        val originalManifest = manifest.readBytes()

        store.removeFile(group.id, other.files.single())
        store.removeFile(UUID.randomUUID().toString(), group.files.single())

        assertContentEquals(originalManifest, manifest.readBytes())
        assertEquals(listOf(group, other), ReviewerPetMediaStore(context).groups())
        assertTrue(copy.isFile)
        assertTrue(assertNotNull(store.mediaFile(other.id, other.files.single())).isFile)
    }

    @Test
    fun `deleting the last available file removes a group with missing media references`() {
        val group = assertNotNull(store.createGroup("Pet", listOf(image("available.png"), image("missing.png"))))
        val firstCopy = assertNotNull(store.mediaFile(group.id, group.files.first()))
        assertTrue(assertNotNull(store.mediaFile(group.id, group.files.last())).delete())
        assertEquals(listOf(group.files.first()), store.groups().single().files)

        store.removeFile(group.id, group.files.first())

        assertTrue(ReviewerPetMediaStore(context).groups().isEmpty())
        assertFalse(firstCopy.parentFile!!.exists())
    }

    @Test
    fun `deleting a group with local file references preserves the original files`() {
        val source = image("local.png")
        val original = File(source.path!!)
        val bytes = original.readBytes()
        val groupId = UUID.randomUUID().toString()
        val root = File(context.filesDir, "reviewer-pets").apply { mkdirs() }
        File(root, "groups.json").writeText(
            JSONArray()
                .put(
                    JSONObject().apply {
                        put("id", groupId)
                        put("name", "Local references")
                        put("enabled", true)
                        put("files", JSONArray(listOf(original.absolutePath, source.toString())))
                    },
                ).toString(),
        )

        store.removeFile(groupId, original.absolutePath)
        assertContentEquals(bytes, original.readBytes())
        store.deleteGroup(groupId)

        assertTrue(store.groups().isEmpty())
        assertTrue(ReviewerPetMediaStore(context).groups().isEmpty())
        assertContentEquals(bytes, original.readBytes())
    }

    @Test
    fun `deleting a group does not follow a directory link to another group`() {
        val first = assertNotNull(store.createGroup("First", listOf(image("link-first.png"))))
        val second = assertNotNull(store.createGroup("Second", listOf(image("link-second.png"))))
        val firstFile = assertNotNull(store.mediaFile(first.id, first.files.single()))
        val secondFile = assertNotNull(store.mediaFile(second.id, second.files.single()))
        val original = secondFile.readBytes()
        val firstDirectory = firstFile.parentFile!!
        assertTrue(firstFile.delete())
        assertTrue(firstDirectory.delete())
        linkDirectory(firstDirectory, secondFile.parentFile!!)

        store.deleteGroup(first.id)

        assertContentEquals(original, secondFile.readBytes())
        assertEquals(listOf(second), store.groups())
    }

    @Test
    fun `deleting a group does not follow a media root link to local files`() {
        val group = assertNotNull(store.createGroup("Pet", listOf(image("root-link.png"))))
        val root = File(context.filesDir, "reviewer-pets")
        val originals = File(context.filesDir, "local-originals")
        val bytes = assertNotNull(store.mediaFile(group.id, group.files.single())).readBytes()
        assertTrue(root.renameTo(originals))
        linkDirectory(root, originals)

        store.deleteGroup(group.id)

        assertContentEquals(bytes, File(originals, "${group.id}/${group.files.single()}").readBytes())
        assertEquals(1, JSONArray(File(originals, "groups.json").readText()).length())
    }

    @Test
    fun `resource interceptor rejects unlisted files traversal other schemes and credentials`() {
        val group = assertNotNull(store.createGroup("Pet", listOf(image("safe.png"))))
        val media = store.enabledMedia().single()
        assertNull(store.intercept(Uri.parse("https://example.org/${group.id}/${media.fileName}")))
        listOf(
            "https://${ReviewerPetMediaStore.HOST}/missing.png",
            "https://${ReviewerPetMediaStore.HOST}/${group.id}/../../groups.json",
            "https://${ReviewerPetMediaStore.HOST}/${group.id}/unlisted.png",
            "http://${ReviewerPetMediaStore.HOST}/${group.id}/${media.fileName}",
            "https://user@${ReviewerPetMediaStore.HOST}/${group.id}/${media.fileName}",
            "${media.url}?query=ignored",
        ).forEach { url ->
            val response = assertNotNull(store.intercept(Uri.parse(url)))
            assertEquals(404, response.statusCode, url)
            assertEquals(0, response.data.use { it.readBytes().size })
        }
    }

    private fun linkDirectory(
        link: File,
        target: File,
    ) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (exception: IOException) {
            assumeNoException("Symbolic links are unavailable on this host", exception)
        } catch (exception: UnsupportedOperationException) {
            assumeNoException("Symbolic links are unavailable on this host", exception)
        }
    }

    private fun image(name: String): Uri {
        val source = temporaryFolder.newFile(name)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(source)
    }
}
