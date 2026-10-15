// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.preferences

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.text.InputFilter
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.reviewer.pets.ReviewerPetMedia
import com.ichi2.anki.reviewer.pets.ReviewerPetMediaGroup
import com.ichi2.anki.reviewer.pets.ReviewerPetMediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream

/** The platform document picker grants temporary read access; copied files need no persistent URI permission. */
class ReviewerPetMediaFragment : ReviewerPetMediaSettingsFragment() {
    private var pendingName: String? = null

    private val selectFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val name = pendingName
            pendingName = null
            if (name != null && uris.isNotEmpty()) {
                mutate {
                    val group = mediaStore.createGroup(name, uris)
                    reportImport(group?.files?.size ?: 0, uris.size)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingName = savedInstanceState?.getString(PENDING_NAME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PENDING_NAME, pendingName)
    }

    override fun refresh() {
        preferenceScreen.removeAll()
        val groups = mediaStore.groups()
        if (groups.none { it.enabled && it.files.isNotEmpty() }) {
            preferenceScreen.addPreference(
                Preference(requireContext()).apply {
                    title = getString(R.string.reviewer_pet_empty)
                    summary = getString(R.string.reviewer_pet_empty_summary)
                    isSelectable = false
                },
            )
        }
        preferenceScreen.addPreference(
            action(R.string.reviewer_pet_add_group, R.drawable.ic_add) {
                askName("") { name ->
                    pendingName = name
                    selectFiles.launch(arrayOf("image/*", "video/*"))
                }
            },
        )
        groups.forEach { group ->
            val category = PreferenceCategory(requireContext())
            preferenceScreen.addPreference(category)
            category.addPreference(
                Preference(requireContext()).apply {
                    title = group.name
                    summary =
                        getString(
                            R.string.reviewer_pet_group_summary,
                            group.files.size,
                            getString(if (group.enabled) R.string.reviewer_pet_state_enabled else R.string.reviewer_pet_state_disabled),
                        )
                    fragment = ReviewerPetGroupFragment::class.java.name
                    extras.putString(ReviewerPetGroupFragment.GROUP_ID, group.id)
                    setIcon(R.drawable.ic_image)
                    addThumbnail(this, group)
                },
            )
            category.addPreference(groupSwitch(group))
            category.addPreference(deleteGroupAction(group))
        }
        updateEnabled()
    }

    companion object {
        private const val PENDING_NAME = "pendingPetGroupName"
    }
}

class ReviewerPetGroupFragment : ReviewerPetMediaSettingsFragment() {
    private val groupId: String get() = arguments?.getString(GROUP_ID).orEmpty()
    private val selectFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) mutate { reportImport(mediaStore.addFiles(groupId, uris), uris.size) }
        }

    override fun onResume() {
        super.onResume()
        // A deletion can finish while this screen is being recreated, before its navigation callback runs.
        if (mediaStore.groups().none { it.id == groupId }) parentFragmentManager.popBackStack()
    }

    override fun refresh() {
        preferenceScreen.removeAll()
        val group = mediaStore.groups().firstOrNull { it.id == groupId } ?: return
        preferenceScreen.title = group.name
        if (view != null) binding.toolbar.title = group.name
        preferenceScreen.addPreference(groupSwitch(group))
        preferenceScreen.addPreference(
            action(R.string.reviewer_pet_rename_group, R.drawable.ic_mode_edit_white) {
                askName(group.name) { name -> mutate { mediaStore.renameGroup(groupId, name) } }
            },
        )
        preferenceScreen.addPreference(
            action(R.string.reviewer_pet_add_files, R.drawable.ic_add) {
                selectFiles.launch(arrayOf("image/*", "video/*"))
            },
        )
        preferenceScreen.addPreference(
            deleteGroupAction(group, close = true),
        )
        if (group.files.isEmpty()) {
            preferenceScreen.addPreference(
                Preference(requireContext()).apply {
                    title = getString(R.string.reviewer_pet_no_files)
                    isSelectable = false
                },
            )
        }
        group.files.forEachIndexed { index, fileName ->
            val category = PreferenceCategory(requireContext()).apply { key = "petMediaFile:$fileName" }
            preferenceScreen.addPreference(category)
            category.addPreference(
                Preference(requireContext()).apply {
                    key = "petMediaPreview:$fileName"
                    title = getString(R.string.reviewer_pet_file, index + 1)
                    summary = fileName.substringAfterLast('.').uppercase()
                    setIcon(R.drawable.ic_image)
                    addThumbnail(this, group.copy(files = listOf(fileName)))
                    setOnPreferenceClickListener {
                        preview(group, fileName)
                        true
                    }
                },
            )
            category.addPreference(
                action(R.string.reviewer_pet_remove_file, R.drawable.ic_delete) {
                    val lastFile = group.files.size == 1
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.reviewer_pet_remove_file)
                        .setMessage(
                            if (lastFile) {
                                getString(R.string.reviewer_pet_remove_last_file_confirm, group.name)
                            } else {
                                getString(R.string.reviewer_pet_remove_file_confirm)
                            },
                        ).setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.reviewer_pet_remove_file) { _, _ ->
                            mutate(close = lastFile) { mediaStore.removeFile(groupId, fileName) }
                        }.show()
                }.apply { key = "petMediaRemove:$fileName" },
            )
        }
        updateEnabled()
    }

    companion object {
        const val GROUP_ID = "petMediaGroupId"
    }
}

abstract class ReviewerPetMediaSettingsFragment : SettingsFragment() {
    override val preferenceResource: Int = R.xml.preferences_reviewer_pet_media
    override val analyticsScreenNameConstant: String = "prefs.studyScreen.pet.media"
    private var mutating = false
    protected val mediaStore: ReviewerPetMediaStore by lazy { ReviewerPetMediaStore(requireContext().applicationContext) }

    override fun initSubscreen() = refresh()

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: android.content.SharedPreferences,
        key: String?,
    ) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        if (key == getString(R.string.new_reviewer_options_key)) updateEnabled()
    }

    protected abstract fun refresh()

    protected fun updateEnabled() {
        preferenceScreen.isEnabled =
            !mutating && preferenceManager.sharedPreferences?.getBoolean(getString(R.string.new_reviewer_options_key), false) == true
    }

    protected fun action(
        title: Int,
        icon: Int,
        block: () -> Unit,
    ): Preference =
        Preference(requireContext()).apply {
            setTitle(title)
            setIcon(icon)
            setOnPreferenceClickListener {
                block()
                true
            }
        }

    protected fun groupSwitch(group: ReviewerPetMediaGroup): SwitchPreferenceCompat =
        SwitchPreferenceCompat(requireContext()).apply {
            title = getString(R.string.reviewer_pet_enabled_group)
            isPersistent = false
            isChecked = group.enabled
            isEnabled = group.files.isNotEmpty()
            setOnPreferenceChangeListener { enabled: Boolean ->
                mutate { mediaStore.setEnabled(group.id, enabled) }
            }
        }

    protected fun deleteGroupAction(
        group: ReviewerPetMediaGroup,
        close: Boolean = false,
    ): Preference =
        action(R.string.reviewer_pet_delete_group, R.drawable.ic_delete) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.reviewer_pet_delete_group)
                .setMessage(getString(R.string.reviewer_pet_delete_group_confirm, group.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reviewer_pet_delete_group) { _, _ ->
                    mutate(close = close) { mediaStore.deleteGroup(group.id) }
                }.show()
        }

    protected fun mutate(
        close: Boolean = false,
        operation: suspend () -> Unit,
    ) {
        if (mutating) return
        mutating = true
        lifecycleScope.launch {
            preferenceScreen.isEnabled = false
            var closed = false
            try {
                withContext(Dispatchers.IO) { operation() }
                if (close && isAdded) {
                    closed = true
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.w(exception, "Unable to save reviewer pet media")
                if (isAdded) showThemedToast(requireContext(), R.string.reviewer_pet_operation_failed, shortLength = false)
            } finally {
                mutating = false
                if (isAdded && !closed) refresh()
            }
        }
    }

    protected suspend fun reportImport(
        imported: Int,
        selected: Int,
    ) {
        withContext(Dispatchers.Main) {
            if (imported == 0) {
                showThemedToast(requireContext(), R.string.reviewer_pet_import_failed, shortLength = false)
            } else if (imported != selected) {
                showThemedToast(
                    requireContext(),
                    getString(R.string.reviewer_pet_import_partial, imported, selected),
                    shortLength = false,
                )
            }
        }
    }

    protected fun askName(
        initial: String,
        onName: (String) -> Unit,
    ) {
        val input =
            EditText(requireContext()).apply {
                setText(initial)
                hint = getString(R.string.reviewer_pet_group_name_hint)
                isSingleLine = true
                filters = arrayOf(InputFilter.LengthFilter(80))
                setSelectAllOnFocus(true)
            }
        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.reviewer_pet_group_name)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> onName(input.text.toString().trim()) }
                .create()
        dialog.setOnShowListener {
            val button = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            button.isEnabled = input.text.toString().isNotBlank()
            input.addTextChangedListener { button.isEnabled = it.toString().isNotBlank() }
        }
        dialog.show()
    }

    protected fun addThumbnail(
        preference: Preference,
        group: ReviewerPetMediaGroup,
    ) {
        val fileName = group.files.firstOrNull() ?: return
        lifecycleScope.launch {
            val bitmap =
                withContext(Dispatchers.IO) {
                    val file = mediaStore.mediaFile(group.id, fileName) ?: return@withContext null
                    if (fileName.endsWith(".mp4") || fileName.endsWith(".webm")) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            val frame = retriever.getFrameAtTime(-1) ?: return@withContext null
                            val ratio = (128f / maxOf(frame.width, frame.height)).coerceAtMost(1f)
                            val thumbnail =
                                Bitmap.createScaledBitmap(
                                    frame,
                                    (frame.width * ratio).toInt().coerceAtLeast(1),
                                    (
                                        frame.height *
                                            ratio
                                    ).toInt().coerceAtLeast(1),
                                    true,
                                )
                            if (thumbnail !== frame) frame.recycle()
                            thumbnail
                        } catch (exception: Exception) {
                            Timber.w(exception, "Unable to load reviewer pet video thumbnail")
                            null
                        } finally {
                            retriever.release()
                        }
                    } else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                        while (bounds.outWidth / options.inSampleSize > 128 ||
                            bounds.outHeight / options.inSampleSize > 128
                        ) {
                            options.inSampleSize *= 2
                        }
                        BitmapFactory.decodeFile(file.absolutePath, options)
                    }
                }
            if (bitmap != null && isAdded) preference.icon = BitmapDrawable(resources, bitmap)
        }
    }

    protected fun preview(
        group: ReviewerPetMediaGroup,
        fileName: String,
    ) {
        val mime =
            if (fileName.endsWith(".mp4")) {
                "video/mp4"
            } else if (fileName.endsWith(".webm")) {
                "video/webm"
            } else {
                "image/*"
            }
        val media = ReviewerPetMedia(group.id, fileName, mime)
        val webView =
            WebView(requireContext()).apply {
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? =
                            if (request.method == "GET") {
                                mediaStore.intercept(request.url)
                                    ?: WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                            } else {
                                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                            }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = true
                    }
                val tag =
                    if (mime.startsWith(
                            "video/",
                        )
                    ) {
                        "<video controls autoplay loop muted src='${media.url}'></video>"
                    } else {
                        "<img src='${media.url}' alt=''>"
                    }
                loadDataWithBaseURL(
                    "https://${ReviewerPetMediaStore.HOST}/",
                    """
                    <!doctype html>
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <meta http-equiv="Content-Security-Policy"
                        content="default-src 'none'; img-src https://${ReviewerPetMediaStore.HOST};
                        media-src https://${ReviewerPetMediaStore.HOST}; style-src 'unsafe-inline'">
                    <style>
                    body{margin:0;display:flex;align-items:center;justify-content:center;height:100vh}
                    img,video{max-width:100%;max-height:100%;object-fit:contain}
                    </style>$tag
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.reviewer_pet_preview)
                .setView(webView)
                .setPositiveButton(android.R.string.ok, null)
                .create()
        dialog.setOnDismissListener {
            webView.stopLoading()
            webView.destroy()
        }
        dialog.show()
        webView.layoutParams = webView.layoutParams.apply { height = (300 * resources.displayMetrics.density).toInt() }
    }
}
