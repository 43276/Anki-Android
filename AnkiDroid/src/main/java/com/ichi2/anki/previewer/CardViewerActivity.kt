// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.previewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.fragment.app.Fragment
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.ui.windows.reviewer.ReviewerFragment
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

/**
 * @see PreviewerFragment
 * @see TemplatePreviewerFragment
 */
class CardViewerActivity : SingleFragmentActivity() {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        (fragment as? ReviewerFragment)?.onPetUserActivity()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        (fragment as? ReviewerFragment)?.onPetUserActivity()
        return super.dispatchKeyEvent(event)
    }

    companion object {
        fun getIntent(
            context: Context,
            fragmentClass: KClass<out Fragment>,
            arguments: Bundle? = null,
        ): Intent =
            Intent(context, CardViewerActivity::class.java).apply {
                putExtra(EXTRA_FRAGMENT_NAME, fragmentClass.jvmName)
                putExtra(EXTRA_FRAGMENT_ARGS, arguments)
            }
    }
}
