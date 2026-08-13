/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.textfield.TextInputLayout
import com.hippo.ehviewer.R
import com.hippo.util.AppHelper

class EditTextDialogBuilder @SuppressLint("InflateParams") constructor(
    context: Context?,
    text: String?,
    hint: String?
) : AlertDialog.Builder(
    context!!
), OnEditorActionListener {
    private val mTextInputLayout: TextInputLayout
    val editText: EditText
    private var mDialog: AlertDialog? = null
    private var mDismissPending = false

    init {
        val view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_edittext_builder, null)
        setView(view)
        mTextInputLayout = view as TextInputLayout
        editText = view.findViewById(R.id.edit_text)
        editText.setText(text)
        editText.setSelection(editText.text.length)
        editText.setOnEditorActionListener(this)
        mTextInputLayout.hint = hint
    }

    val text: String
        get() = editText.text.toString()

    fun setError(error: CharSequence?) {
        mTextInputLayout.error = error
    }

    override fun create(): AlertDialog {
        mDialog = super.create()
        mDialog!!.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        mDialog!!.window?.decorView?.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    recoverHostLayout()
                }
            }
        )
        return mDialog!!
    }

    /** Waits for the IME to hide, closes the dialog, then runs the optional UI mutation. */
    fun dismiss() {
        dismiss(null)
    }

    fun dismiss(afterDismiss: Runnable?) {
        val dialog = mDialog ?: return
        if (mDismissPending) {
            return
        }
        mDismissPending = true
        val window = dialog.window
        val decor = window?.decorView
        AppHelper.hideSoftInput(dialog)
        if (window == null || decor == null) {
            finishDismiss(dialog, afterDismiss)
            return
        }
        WindowInsetsControllerCompat(window, decor).hide(WindowInsetsCompat.Type.ime())
        val startedAt = SystemClock.uptimeMillis()
        val waitForIme = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) {
                    afterDismiss?.run()
                    return
                }
                val insets = ViewCompat.getRootWindowInsets(decor)
                val imeHidden = insets != null &&
                        !insets.isVisible(WindowInsetsCompat.Type.ime())
                val elapsed = SystemClock.uptimeMillis() - startedAt
                val hideSettled = imeHidden && elapsed >= IME_HIDE_SETTLE_TIME_MS
                val timedOut = elapsed >= IME_HIDE_TIMEOUT_MS
                if (hideSettled || timedOut) {
                    finishDismiss(dialog, afterDismiss)
                } else {
                    decor.postOnAnimation(this)
                }
            }
        }
        decor.postOnAnimation(waitForIme)
    }

    private fun finishDismiss(dialog: AlertDialog, afterDismiss: Runnable?) {
        dialog.dismiss()
        val activity = findActivity(context)
        val hostDecor = activity?.window?.decorView
        if (hostDecor == null) {
            afterDismiss?.run()
            return
        }
        hostDecor.post {
            refreshHostLayout(activity, hostDecor)
            afterDismiss?.run()
        }
    }

    private fun recoverHostLayout() {
        val activity = findActivity(context) ?: return
        val decor = activity.window?.decorView ?: return
        val refreshLayout = Runnable {
            refreshHostLayout(activity, decor)
        }
        decor.post(refreshLayout)
        // Some keyboards finish their hide animation after the dialog window is detached.
        decor.postDelayed(refreshLayout, IME_LAYOUT_RECOVERY_DELAY_MS)
    }

    private fun refreshHostLayout(activity: Activity, decor: View) {
        if (activity.isFinishing ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)
        ) {
            return
        }
        ViewCompat.requestApplyInsets(decor)
        decor.requestLayout()
        decor.invalidate()
    }

    private tailrec fun findActivity(context: Context?): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }

    override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent?): Boolean {
        if (event != null && mDialog != null) {
            val button = mDialog!!.getButton(DialogInterface.BUTTON_POSITIVE)
            button?.performClick()
            return true
        } else {
            return false
        }
    }

    private companion object {
        const val IME_LAYOUT_RECOVERY_DELAY_MS = 300L
        const val IME_HIDE_SETTLE_TIME_MS = 250L
        const val IME_HIDE_TIMEOUT_MS = 750L
    }
}
