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

package com.hippo.ehviewer.ui.scene.download.part

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.core.graphics.drawable.toDrawable
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.ui.scene.download.DownloadsScene
import com.hippo.widget.LoadImageView

/** Upstream album covers use the imported copy and their original cache/decoder. */
class OriginalAlbumThumbnailLoader(private val mScene: DownloadsScene) {
    private val TAG = OriginalAlbumThumbnailLoader::class.java.simpleName
    private val thumbnailCache: MutableMap<String?, Bitmap?> = HashMap()

    fun loadLocalAlbumThumbnail(thumb: LoadImageView, info: DownloadInfo) {
        val cacheKey = DownloadAlbumImporter.URI_PREFIX + info.gid
        thumb.setTag(R.id.thumb, cacheKey)
        val cachedThumbnail = thumbnailCache[cacheKey]
        if (cachedThumbnail != null && !cachedThumbnail.isRecycled) {
            val resources = mScene.getResources2()
            if (resources != null) {
                thumb.load(BitmapDrawable(resources, cachedThumbnail))
            } else {
                thumb.setImageBitmap(cachedThumbnail)
            }
            return
        }

        thumb.load(Color.TRANSPARENT.toDrawable())
        Thread(Runnable {
            val thumbnail = decodeLocalAlbumThumb(info)
            mScene.runOnUiThread(Runnable {
                if (cacheKey != thumb.getTag(R.id.thumb)) {
                    return@Runnable
                }
                if (thumbnail != null && !thumbnail.isRecycled) {
                    thumbnailCache[cacheKey] = thumbnail
                    val resources = mScene.getResources2()
                    if (resources != null) {
                        thumb.load(BitmapDrawable(resources, thumbnail))
                    } else {
                        thumb.setImageBitmap(thumbnail)
                    }
                }
            })
        }).start()
    }

    private fun decodeLocalAlbumThumb(info: DownloadInfo): Bitmap? {
        val dir = SpiderDen.getGalleryDownloadDir(info)
        if (dir == null || !dir.isDirectory()) {
            return null
        }
        var thumbFile = dir.findFile(".thumb")
        if (thumbFile == null) {
            thumbFile = SpiderDen.findImageFile(dir, 0)
        }
        if (thumbFile == null) {
            return null
        }
        try {
            thumbFile.openInputStream().use { boundsStream ->
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeStream(boundsStream, null, options)
                val thumbnailSize = 150
                var sampleSize = 1
                if (options.outHeight > thumbnailSize || options.outWidth > thumbnailSize) {
                    val halfHeight = options.outHeight / 2
                    val halfWidth = options.outWidth / 2
                    while ((halfHeight / sampleSize) >= thumbnailSize && (halfWidth / sampleSize) >= thumbnailSize) {
                        sampleSize *= 2
                    }
                }
                options.inJustDecodeBounds = false
                options.inSampleSize = sampleSize
                options.inPreferredConfig = Bitmap.Config.RGB_565
                thumbFile.openInputStream().use { decodeStream ->
                    return BitmapFactory.decodeStream(decodeStream, null, options)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode local album thumbnail for gid=" + info.gid, e)
            return null
        }
    }
}
