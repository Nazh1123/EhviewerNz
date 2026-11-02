package com.hippo.ehviewer.gallery

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.content.FileProvider
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.GalleryActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Linux /proc resolution using a real FileProvider, without touching user images. */
@RunWith(AndroidJUnit4::class)
class ExternalImageAccessDeviceTest {
    private fun resolveDescriptor(context: Context, uri: Uri): File? =
        (ExternalImageFileResolver::class.java.getDeclaredMethod(
            "resolveFileDescriptor", Context::class.java, Uri::class.java
        ).apply { isAccessible = true }.invoke(null, context, uri) as? File)?.canonicalFile

    @Test
    fun externalStorageFileDescriptorResolvesToDirectlyReadableFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(checkNotNull(context.getExternalFilesDir(null)),
            "external-image-test-${System.nanoTime()}").apply { check(mkdir()) }
        try {
            val image = File(root, "图片 100%2F #+.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val uri = FileProvider.getUriForFile(context, BuildConfig.FILE_PROVIDER_AUTHORITY, image)
            assertEquals(image.canonicalFile, resolveDescriptor(context, uri))
            assertTrue(ExternalImageFileResolver.isReadableDirectory(image.parentFile))
        } finally { root.deleteRecursively() }
    }

    @Test(timeout = 30000)
    fun rootAliasUriOpensSiblingsAndStartsAtSelectedImage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "external-image-test-${System.nanoTime()}")
            .apply { check(mkdir()) }
        val folder = File(root, "图片 100%2F #+").apply { check(mkdir()) }
        val originalGuide = Settings.getGuideGallery()
        var activity: GalleryActivity? = null
        try {
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                for (name in listOf("2.png", "10.png")) {
                    File(folder, name).outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
            } finally { bitmap.recycle() }
            val selected = File(folder, "10.png")
            val uri = FileProvider.getUriForFile(context, BuildConfig.FILE_PROVIDER_AUTHORITY, selected)
            // /root/... has the same alias-before-absolute-path layout as /storage_root/...
            assertTrue(uri.path!!.startsWith("/root/"))
            // Our app's older FileProvider also exposes _data. Invoke the fallback
            // explicitly so this test cannot pass without exercising Linux readlink.
            assertEquals(selected.canonicalFile, resolveDescriptor(context, uri))
            assertEquals(selected.canonicalFile,
                ExternalImageFileResolver.resolve(context, uri)?.canonicalFile)

            Settings.putGuideGallery(false)
            val monitor = instrumentation.addMonitor(GalleryActivity::class.java.name, null, false)
            try {
                val command = "am start -W -n ${context.packageName}/${GalleryActivity::class.java.name}" +
                    " -a ${Intent.ACTION_VIEW} -d $uri -t image/png"
                instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
                activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? GalleryActivity
            } finally { instrumentation.removeMonitor(monitor) }
            val reader = checkNotNull(activity) { "Reader did not open" }
            val provider = GalleryActivity::class.java.getDeclaredField("mGalleryProvider")
                .apply { isAccessible = true }.get(reader) as DirGalleryProvider
            assertFalse(provider is SingleImageGalleryProvider)
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (provider.size() < 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertEquals(2, provider.size())
            assertEquals("2.png", provider.getFilename(0))
            assertEquals("10.png", provider.getFilename(1))
            assertEquals(1, provider.startPage)
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            Settings.putGuideGallery(originalGuide)
            root.deleteRecursively()
        }
    }
}
