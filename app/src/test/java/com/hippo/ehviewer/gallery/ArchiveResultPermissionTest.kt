package com.hippo.ehviewer.gallery

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ArchiveResultPermissionTest {
    @Test fun folderPickerRequestsPersistentReadWriteAndCancellationDoesNotStartProcessing() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        val activity = controller.get().apply { setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light) }
        controller.setup()
        var started = false
        try {
            val fragment = ArchiveResultPermissionFragment().apply {
                arguments = Bundle().apply { putString("archive", "content://documents/document/opaque-archive") }
                callbacks.add(Runnable { started = true })
            }
            activity.supportFragmentManager.beginTransaction().add(fragment, "permission").commitNow()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val request = shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, request.intent.action)
            assertTrue(request.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(request.intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertTrue(request.intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
            shadowOf(activity).receiveResult(request.intent, Activity.RESULT_CANCELED, Intent())
            shadowOf(Looper.getMainLooper()).idle()
            activity.supportFragmentManager.executePendingTransactions()
            assertFalse(started)
            assertNull(activity.supportFragmentManager.findFragmentByTag("permission"))
        } finally { controller.pause().stop().destroy() }
    }
}
