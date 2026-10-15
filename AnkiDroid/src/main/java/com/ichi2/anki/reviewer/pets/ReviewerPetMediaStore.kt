// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer.pets

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID

data class ReviewerPetMediaGroup(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val files: List<String>,
)

data class ReviewerPetMedia(
    val groupId: String,
    val fileName: String,
    val mimeType: String,
) {
    val url: String get() = "https://${ReviewerPetMediaStore.HOST}/$groupId/$fileName"
}

/** Owns copied pet files separately from collection media. All manifest mutations are atomic. */
class ReviewerPetMediaStore(
    private val context: Context,
) {
    private val root = File(context.filesDir, "reviewer-pets")
    private val manifest = AtomicFile(File(root, "groups.json"))

    fun groups(): List<ReviewerPetMediaGroup> =
        synchronized(lock) {
            readGroups().map { group ->
                val validFiles = group.files.filter { file(group.id, it)?.isFile == true }
                group.copy(enabled = group.enabled && validFiles.isNotEmpty(), files = validFiles)
            }
        }

    /** Every enabled file appears once, so choosing uniformly from this list preserves file-level probability. */
    fun enabledMedia(): List<ReviewerPetMedia> =
        groups().filter { it.enabled }.flatMap { group ->
            group.files.map { ReviewerPetMedia(group.id, it, mimeType(it)!!) }
        }

    fun createGroup(
        name: String,
        uris: List<Uri>,
    ): ReviewerPetMediaGroup? =
        synchronized(lock) {
            val cleanName = name.trim().takeIf { it.isNotEmpty() } ?: return@synchronized null
            val id = UUID.randomUUID().toString()
            val imported = copyFiles(id, uris)
            if (imported.isEmpty()) {
                removeGroupDirectory(id)
                return@synchronized null
            }
            val group = ReviewerPetMediaGroup(id, cleanName, true, imported)
            try {
                saveGroups(readGroups() + group)
            } catch (exception: IOException) {
                removeGroupDirectory(id)
                throw exception
            }
            group
        }

    fun addFiles(
        groupId: String,
        uris: List<Uri>,
    ): Int =
        synchronized(lock) {
            val groups = readGroups()
            val group = groups.firstOrNull { it.id == groupId } ?: return@synchronized 0
            val imported = copyFiles(groupId, uris)
            if (imported.isEmpty()) return@synchronized 0
            try {
                saveGroups(groups.map { if (it.id == groupId) it.copy(files = group.files + imported) else it })
            } catch (exception: IOException) {
                imported.forEach { file(groupId, it)?.delete() }
                throw exception
            }
            imported.size
        }

    fun renameGroup(
        groupId: String,
        name: String,
    ) {
        val cleanName = name.trim().takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) { saveGroups(readGroups().map { if (it.id == groupId) it.copy(name = cleanName) else it }) }
    }

    fun setEnabled(
        groupId: String,
        enabled: Boolean,
    ) {
        synchronized(lock) {
            saveGroups(groups().map { if (it.id == groupId) it.copy(enabled = enabled && it.files.isNotEmpty()) else it })
        }
    }

    fun removeFile(
        groupId: String,
        fileName: String,
    ) {
        synchronized(lock) {
            val groups = readGroups()
            val group = groups.firstOrNull { it.id == groupId && fileName in it.files } ?: return
            val remaining = group.files.filter { it != fileName && file(groupId, it)?.isFile == true }
            if (remaining.isEmpty()) {
                saveGroups(groups.filterNot { it.id == groupId })
                removeGroupDirectory(groupId)
            } else {
                saveGroups(groups.map { if (it.id == groupId) it.copy(files = remaining) else it })
                file(groupId, fileName)?.delete()
            }
        }
    }

    /** Removes only the group's private directory; original local paths and URIs are never deletion targets. */
    fun deleteGroup(groupId: String) {
        synchronized(lock) {
            val groups = readGroups()
            if (groups.none { it.id == groupId }) return
            saveGroups(groups.filterNot { it.id == groupId })
            removeGroupDirectory(groupId)
        }
    }

    /** Used for a first-frame thumbnail by the settings screen, never exposes an arbitrary private path. */
    fun mediaFile(
        groupId: String,
        fileName: String,
    ): File? =
        synchronized(lock) {
            if (groups().none { it.id == groupId && fileName in it.files }) return@synchronized null
            file(groupId, fileName)
        }

    /** The caller must also restrict requests to GET. Unknown paths on this host are consumed with 404. */
    fun intercept(uri: Uri): WebResourceResponse? {
        if (uri.host != HOST) return null
        if (uri.scheme != "https" || uri.authority != HOST || uri.query != null || uri.fragment != null) return notFound()
        return synchronized(lock) {
            val parts = uri.pathSegments
            if (parts.size != 2) return@synchronized notFound()
            val media = mediaFile(parts[0], parts[1]) ?: return@synchronized notFound()
            try {
                WebResourceResponse(mimeType(parts[1]), null, media.inputStream())
            } catch (exception: IOException) {
                Timber.w(exception, "Unable to open reviewer pet media")
                notFound()
            }
        }
    }

    private fun readGroups(): List<ReviewerPetMediaGroup> {
        if (!isRootSafe() || !manifestPathsSafe()) return emptyList()
        if (!manifest.baseFile.exists() && !File(root, "groups.json.bak").exists()) return emptyList()
        return try {
            val array = JSONArray(manifest.openRead().bufferedReader().use { it.readText() })
            (0 until array.length())
                .mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id").takeIf { groupIdPattern.matches(it) } ?: return@mapNotNull null
                    val name = item.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val files = item.optJSONArray("files") ?: JSONArray()
                    ReviewerPetMediaGroup(
                        id,
                        name,
                        item.optBoolean("enabled", false),
                        (0 until files.length())
                            .mapNotNull {
                                files.optString(it).takeIf { name ->
                                    fileNamePattern.matches(name) &&
                                        mimeType(name) != null
                                }
                            }.distinct(),
                    )
                }.distinctBy { it.id }
        } catch (exception: Exception) {
            Timber.w(exception, "Unable to read reviewer pet media manifest")
            emptyList()
        }
    }

    private fun saveGroups(groups: List<ReviewerPetMediaGroup>) {
        if (!isRootSafe() || !manifestPathsSafe()) throw IOException("Unsafe pet media directory")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Unable to create pet media directory")
        val array = JSONArray()
        groups.forEach { group ->
            array.put(
                JSONObject().apply {
                    put("id", group.id)
                    put("name", group.name)
                    put("enabled", group.enabled)
                    put("files", JSONArray(group.files))
                },
            )
        }
        val stream = manifest.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            manifest.finishWrite(stream)
        } catch (exception: Exception) {
            manifest.failWrite(stream)
            throw exception
        }
    }

    private fun copyFiles(
        groupId: String,
        uris: List<Uri>,
    ): List<String> {
        val directory = groupDirectory(groupId) ?: throw IOException("Unsafe pet media group")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to create pet media group")
        return uris.distinct().mapNotNull { uri ->
            var destination: File? = null
            try {
                val resolver = context.contentResolver
                val type = resolver.getType(uri)
                val extension =
                    extensionsByMime[type] ?: displayName(uri).substringAfterLast('.', "").lowercase().takeIf { it in mimeByExtension }
                        ?: return@mapNotNull null
                // An explicitly non-media type may not be imported by disguising its file extension.
                if (type != null && type !in extensionsByMime && type != "application/octet-stream") return@mapNotNull null
                val fileName = "${UUID.randomUUID()}.$extension"
                val importedFile = File(directory, fileName)
                destination = importedFile
                val input = resolver.openInputStream(uri) ?: return@mapNotNull null
                input.use { source -> importedFile.outputStream().use { source.copyTo(it) } }
                if (!isValidMedia(importedFile)) {
                    importedFile.delete()
                    return@mapNotNull null
                }
                fileName
            } catch (exception: Exception) {
                destination?.delete()
                Timber.w(exception, "Unable to import reviewer pet media")
                null
            }
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun isValidMedia(media: File): Boolean {
        if (media.length() == 0L) return false
        if (mimeType(media.name)?.startsWith("video/") == true) {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(media.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            } finally {
                retriever.release()
            }
        }
        if (!hasImageSignature(media)) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(media.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun hasImageSignature(media: File): Boolean {
        val header =
            media.inputStream().use { source ->
                val bytes = ByteArray(32)
                val count = source.read(bytes)
                bytes.copyOf(count.coerceAtLeast(0)).toString(Charsets.ISO_8859_1)
            }
        return when (media.extension.lowercase()) {
            "png", "apng" -> header.startsWith("\u0089PNG\r\n\u001a\n")
            "jpg", "jpeg" -> header.startsWith("\u00ff\u00d8\u00ff")
            "gif" -> header.startsWith("GIF87a") || header.startsWith("GIF89a")
            "webp" -> header.length >= 12 && header.startsWith("RIFF") && header.substring(8, 12) == "WEBP"
            "avif" ->
                header.length >= 12 && header.substring(4, 8) == "ftyp" &&
                    ("avif" in header.substring(8) || "avis" in header.substring(8))
            "bmp" -> header.startsWith("BM")
            else -> false
        }
    }

    private fun file(
        groupId: String,
        fileName: String,
    ): File? {
        if (!groupIdPattern.matches(groupId) || !fileNamePattern.matches(fileName) || mimeType(fileName) == null) return null
        val directory = groupDirectory(groupId) ?: return null
        val result = File(directory, fileName)
        return result.takeIf { it.canonicalFile.parentFile == directory.canonicalFile }
    }

    private fun isRootSafe(): Boolean = root.canonicalFile == File(context.filesDir.canonicalFile, "reviewer-pets")

    private fun manifestPathsSafe(): Boolean =
        listOf("groups.json", "groups.json.bak", "groups.json.new").all {
            File(root, it).canonicalFile.parentFile == root.canonicalFile
        }

    private fun groupDirectory(groupId: String): File? {
        if (!groupIdPattern.matches(groupId) || !isRootSafe()) return null
        val directory = File(root, groupId)
        return directory.takeIf { it.canonicalFile == File(root.canonicalFile, groupId) }
    }

    /** Imported groups are flat. Unlink unexpected symlinks without traversing their targets. */
    private fun removeGroupDirectory(groupId: String) {
        val directory = groupDirectory(groupId) ?: return
        directory.listFiles()?.forEach { entry ->
            if (!entry.isDirectory || entry.canonicalFile != entry.absoluteFile) entry.delete()
        }
        directory.delete()
    }

    private fun mimeType(fileName: String): String? = mimeByExtension[fileName.substringAfterLast('.').lowercase()]

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))

    companion object {
        const val HOST = "reviewer-pets.invalid"
        private val lock = Any()
        private val groupIdPattern = Regex("[a-f0-9-]{36}")
        private val fileNamePattern = Regex("[a-f0-9-]{36}\\.[a-z0-9]+")
        private val mimeByExtension =
            mapOf(
                "png" to "image/png",
                "apng" to "image/apng",
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "gif" to "image/gif",
                "webp" to "image/webp",
                "avif" to "image/avif",
                "bmp" to "image/bmp",
                "mp4" to "video/mp4",
                "webm" to "video/webm",
            )
        private val extensionsByMime = mimeByExtension.entries.associate { (extension, mime) -> mime to extension }
    }
}
