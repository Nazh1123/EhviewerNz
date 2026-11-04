package com.hippo.ehviewer.gallery

import android.content.Context
import android.os.SystemClock
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhRequestBuilder
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.parser.GalleryDetailParser
import com.hippo.ehviewer.client.parser.GalleryPageParser
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response

/** One cancellable transfer, separate from the spider's reading and whole-gallery writers. */
internal class OriginalImageDownload(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val html = EhApplication.getBrowsingOkHttpClient(app)
    private val redirects = EhApplication.getBrowsingImageOkHttpClient(app).newBuilder().cache(null)
        .callTimeout(30, TimeUnit.SECONDS).build()
    private val images = html.newBuilder().cache(null)
        .callTimeout(Settings.getDownloadTimeout().toLong(), TimeUnit.SECONDS).build()
    private var closed = false
    private var call: Call? = null

    private fun <T> request(client: OkHttpClient, url: String, referer: String?, read: (Response) -> T): T {
        val request = client.newCall(EhRequestBuilder(url, referer).build())
        synchronized(this) {
            if (closed) throw CancellationException()
            call = request
        }
        try {
            return request.execute().use(read)
        } finally {
            synchronized(this) { call = null }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        call?.cancel()
    }

    private fun pageUrl(gallery: GalleryInfo, page: Int, token: String?): String {
        token?.let { return EhUrl.getPageUrl(gallery.gid, page, it) }
        fun previews(number: Int) = request(html,
            EhUrl.getGalleryDetailUrl(gallery.gid, gallery.token, number, false), EhUrl.getReferer()) {
            checkStatus(it)
            GalleryDetailParser.parsePreviewSet(it.body()!!.string())
        }
        val first = previews(0)
        fun find(previews: com.hippo.ehviewer.client.data.PreviewSet): String? =
            (0 until previews.size()).firstOrNull { previews.getPosition(it) == page }?.let(previews::getPageUrlAt)
        find(first)?.let { return it }
        if (first.size() > 0) find(previews(page / first.size()))?.let { return it }
        throw IOException(app.getString(R.string.original_page_unavailable))
    }

    fun transfer(gallery: GalleryInfo, page: Int, token: String?, output: OutputStream,
                 progress: (Long, Long) -> Unit) {
        val referer = pageUrl(gallery, page, token)
        val fullImage = request(html, referer, EhUrl.getGalleryDetailUrl(gallery.gid, gallery.token)) {
            checkStatus(it)
            GalleryPageParser.parse(it.body()!!.string()).also { parsed ->
                if (isQuotaImage(parsed.imageUrl)) throw IOException(app.getString(R.string.error_509))
            }.originImageUrl ?: throw IOException(app.getString(R.string.original_unavailable))
        }
        request(redirects, fullImage, referer) { response ->
            val location = response.header("Location")
            if (location != null && response.isRedirect) {
                val imageUrl = response.request().url().resolve(location)?.toString()
                    ?: throw IOException(app.getString(R.string.original_unavailable))
                if (imageUrl.contains("bounce_login")) throw IOException(app.getString(R.string.original_login_required))
                request(images, imageUrl, referer) { streamImage(it, output, progress) }
            } else {
                streamImage(response, output, progress)
            }
        }
    }

    private fun checkStatus(response: Response) {
        if (response.code() == 509 || isQuotaImage(response.request().url().encodedPath())) {
            throw IOException(app.getString(R.string.error_509))
        }
        if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
    }

    private fun isQuotaImage(url: String?) = url?.substringBefore('?')?.let {
        it.endsWith("/509.gif") || it.endsWith("/509s.gif")
    } == true

    private fun streamImage(response: Response, output: OutputStream, progress: (Long, Long) -> Unit) {
        checkStatus(response)
        val body = response.body() ?: throw IOException(app.getString(R.string.original_unavailable))
        val type = body.contentType()
        if (type?.type() == "text") {
            val message = response.peekBody(8192).string()
            throw IOException(app.getString(if (message.contains("requires GP") || message.contains("sufficient GP"))
                R.string.original_gp_required else R.string.original_unavailable))
        }
        val length = body.contentLength()
        val buffer = ByteArray(32 * 1024)
        var received = 0L
        var lastUpdate = SystemClock.elapsedRealtime()
        progress(received, length)
        body.byteStream().use { input ->
            while (true) {
                if (Thread.currentThread().isInterrupted) throw CancellationException()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                received += count
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 100) {
                    progress(received, length)
                    lastUpdate = now
                }
            }
        }
        if (received == 0L || length >= 0 && length != received) {
            throw IOException(app.getString(R.string.original_incomplete))
        }
        progress(received, length)
    }
}
