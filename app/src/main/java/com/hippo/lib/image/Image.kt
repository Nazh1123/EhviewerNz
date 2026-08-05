package com.hippo.lib.image

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.ImageDecoder.ALLOCATOR_DEFAULT
import android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
import android.graphics.ImageDecoder.DecodeException
import android.graphics.ImageDecoder.ImageInfo
import android.graphics.ImageDecoder.Source
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toDrawable
import com.hippo.ehviewer.EhApplication
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.createBitmap
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.Settings
import java.nio.ByteBuffer


class Image private constructor(
    source: FileInputStream?,
    drawable: Drawable? = null,
    val hardware: Boolean = false,
    val release: () -> Unit? = {},
) {
    private var mObtainedDrawable: Drawable?
    private var mNativeImage: Image1? = null
    private var mBitmap: Bitmap? = null
    private var mReferences = 0

    init {
        mObtainedDrawable = null
        source?.let {
            if (!hardware && Settings.getExperimentalAnimatedWebpEnabled() &&
                Settings.getReadingDirection() != 2 && isAnimatedWebp(source)) {
                source.channel.position(0)
                mNativeImage = Image1.decode(source, false)
                if (mNativeImage != null) return@let
                source.channel.position(0)
            }
            var simpleSize: Int? = null
            if (source.available() > 10485760) {
                simpleSize = source.available() / 10485760 + 1
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val src = ImageDecoder.createSource(
                    source.channel.map(
                        FileChannel.MapMode.READ_ONLY, 0,
                        source.available().toLong()
                    )
                )
                try {
                    mObtainedDrawable =
                        ImageDecoder.decodeDrawable(src) { decoder: ImageDecoder, info: ImageInfo, _: Source ->
                            decoder.allocator =
                                if (hardware) ALLOCATOR_DEFAULT else ALLOCATOR_SOFTWARE
                            // Sadly we must use software memory since we need copy it to tile buffer, fuck glgallery
                            // Idk it will cause how much performance regression
                            val screenSize = min(
                                info.size.width / screenWidth,
                                info.size.height / screenHeight
                            ).coerceAtLeast(1)
                            decoder.setTargetSampleSize(
                                max(screenSize, simpleSize ?: 1)
                            )
                            // Don't
                        }
                } catch (e: DecodeException) {
                    // ImageDecoder 失败时回退到 BitmapFactory
                    try {
                        // 重置流位置以便重新读取
                        source.channel.position(0)
                        if (simpleSize != null) {
                            val option = BitmapFactory.Options().apply {
                                inSampleSize = simpleSize
                            }
                            val bitmap = BitmapFactory.decodeStream(source, null, option)
                            if (bitmap == null) {
                                throw IllegalArgumentException("BitmapFactory.decodeStream 回退解码返回空")
                            }
                            mObtainedDrawable =
                                bitmap.toDrawable(EhApplication.getInstance().resources)
                        } else {
                            mObtainedDrawable = BitmapDrawable.createFromStream(source, null)
                                ?: throw IllegalArgumentException("BitmapDrawable.createFromStream 回退解码返回空")
                        }
                    } catch (fallbackException: Exception) {
                        Analytics.recordException(fallbackException)
                        throw Exception("Android 9 解码失败", e)
                    }
                }
                // Should we lazy decode it?
            } else {
                if (simpleSize != null) {
                    val option = BitmapFactory.Options().apply {
                        inSampleSize = simpleSize
                    }
                    val bitmap = BitmapFactory.decodeStream(source, null, option)
                    if (bitmap == null) {
                        throw IllegalArgumentException("BitmapFactory.decodeStream 返回空")
                    }
                    mObtainedDrawable =
                        BitmapDrawable(EhApplication.getInstance().resources, bitmap)
                } else {
                    mObtainedDrawable = BitmapDrawable.createFromStream(source, null)
                        ?: throw IllegalArgumentException("BitmapDrawable.createFromStream 返回空")
                }
            }
        }
        if (mObtainedDrawable == null && mNativeImage == null) {
            mObtainedDrawable = drawable
                ?: throw IllegalArgumentException("数据解码出错")
        }
    }

    val animated: Boolean
        get() = mNativeImage?.let { it.frameCount > 1 } ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mObtainedDrawable is AnimatedImageDrawable
        } else {
            mObtainedDrawable is AnimationDrawable
        }
    val controllableAnimation: Boolean
        get() = mNativeImage?.let { it.format == Image1.FORMAT_WEBP && it.frameCount > 1 } == true
    val width: Int
        get() = mNativeImage?.width ?: ((mObtainedDrawable as? BitmapDrawable)?.bitmap?.width
            ?: mObtainedDrawable!!.intrinsicWidth)
    val height: Int
        get() = mNativeImage?.height ?: ((mObtainedDrawable as? BitmapDrawable)?.bitmap?.height
            ?: mObtainedDrawable!!.intrinsicHeight)
    val isRecycled: Boolean
        get() = mNativeImage?.isRecycled ?: (mObtainedDrawable == null)

    private var started = false

    @Synchronized
    fun recycle() {
        mNativeImage?.let {
            if (!it.isRecycled) it.recycle()
            mNativeImage = null
            release()
            return
        }
        if (mObtainedDrawable == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (mObtainedDrawable is AnimatedImageDrawable) {
                (mObtainedDrawable as AnimatedImageDrawable?)?.stop()
            }
        }
        if (mObtainedDrawable is BitmapDrawable) {
            (mObtainedDrawable as BitmapDrawable?)?.bitmap?.recycle()
        }
        mObtainedDrawable?.callback = null
        mObtainedDrawable = null
        mBitmap?.recycle()
        mBitmap = null
        release()
    }

    private fun prepareBitmap() {
        if (mBitmap != null) return
        mBitmap = createBitmap(width, height)
    }

    private fun updateBitmap() {
        prepareBitmap()
        mObtainedDrawable!!.draw(Canvas(mBitmap!!))
    }

    @Synchronized
    fun obtain(): Boolean {
        return if (isRecycled) {
            false
        } else {
            ++mReferences
            true
        }
    }

    @Synchronized
    fun release() {
        --mReferences
        if (mReferences <= 0 && isRecycled) {
            recycle()
        }
    }

    fun getDrawable(): Drawable {
        check(mNativeImage == null) { "Native animated WebP has no Drawable" }
        check(obtain()) { "Recycled!" }
        return mObtainedDrawable as Drawable
    }

    fun texImage(init: Boolean, offsetX: Int, offsetY: Int, width: Int, height: Int) {
        check(!hardware) { "Hardware buffer cannot be used in glgallery" }
        mNativeImage?.let {
            it.texImage(init, offsetX, offsetY, width, height)
            return
        }
        try {
            val bitmap: Bitmap = if (animated) {
                updateBitmap()
                mBitmap!!
            } else {
                if (mObtainedDrawable == null) {
                    return
                }
                if (mObtainedDrawable is BitmapDrawable) {
                    (mObtainedDrawable as BitmapDrawable).bitmap
                } else {
                    val stickerBitmap = createBitmap(
                        mObtainedDrawable!!.intrinsicWidth,
                        mObtainedDrawable!!.intrinsicHeight
                    )
                    val canvas = Canvas(stickerBitmap)
                    mObtainedDrawable!!.setBounds(0, 0, stickerBitmap.width, stickerBitmap.height)
                    mObtainedDrawable!!.draw(canvas)
                    stickerBitmap
                }
            }
            nativeTexImage(
                bitmap,
                init,
                offsetX,
                offsetY,
                width,
                height
            )
        } catch (e: ClassCastException) {
            Analytics.recordException(e)
            return
        }
    }

    fun texImageDirect(init: Boolean) {
        check(!hardware) { "Hardware buffer cannot be used in glgallery" }
        mNativeImage?.texImageDirect(init)
            ?: throw IllegalStateException("Direct upload requires native animated WebP")
    }

    fun advanceFrame(): Boolean = mNativeImage?.advanceAndGetLooped() ?: false

    fun seekTo(positionMs: Int): Int = mNativeImage?.seekTo(positionMs) ?: 0

    val currentFramePosition: Int
        get() = mNativeImage?.currentPosition ?: 0

    val totalDuration: Int
        get() = mNativeImage?.totalDuration ?: 0

    fun start() {
        if (mNativeImage != null) return
        if (!started) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (mObtainedDrawable as AnimatedImageDrawable?)?.start()
            }
        }
    }

    val delay: Int
        get() {
            mNativeImage?.let { return it.delay }
            if (animated)
                return 10
            return 0
        }

    @get:SuppressWarnings("deprecation")
    val isOpaque: Boolean
        get() {
            mNativeImage?.let { return it.isOpaque }
            return mObtainedDrawable?.opacity == PixelFormat.OPAQUE
        }

    companion object {
        var screenWidth: Int = 0
        var screenHeight: Int = 0

        @JvmStatic
        fun initialize(ehApplication: EhApplication) {
            screenWidth = ehApplication.resources.displayMetrics.widthPixels
            screenHeight = ehApplication.resources.displayMetrics.heightPixels
        }

        private fun isAnimatedWebp(stream: FileInputStream): Boolean {
            val channel = stream.channel
            val oldPosition = channel.position()
            return try {
                channel.position(0)
                val header = ByteBuffer.allocate(21)
                if (channel.read(header) < 21) return false
                val bytes = header.array()
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                    bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                    bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                    bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() &&
                    bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
                    bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte() &&
                    (bytes[20].toInt() and 0x02) != 0
            } catch (_: Exception) {
                false
            } finally {
                channel.position(oldPosition)
            }
        }

        @JvmStatic
        fun decode(stream: FileInputStream, hardware: Boolean = true): Image? {
            try {
                return Image(stream, hardware = hardware)
            } catch (e: Exception) {
                e.printStackTrace()
                Analytics.recordException(e)
                return null
            }
        }

        @JvmStatic
        fun decode(drawable: Drawable?, hardware: Boolean = true): Image? {
            try {
                return Image(null, drawable, hardware = hardware)
            } catch (e: Exception) {
                e.printStackTrace()
                Analytics.recordException(e)
                return null
            }
        }

//        @JvmStatic
//        fun decode(buffer: ByteBuffer, hardware: Boolean = true, release: () -> Unit? = {}): Image {
//            val src = ImageDecoder.createSource(buffer)
//            return Image(src, hardware = hardware) {
//                release()
//            }
//        }

        @JvmStatic
        fun create(bitmap: Bitmap): Image? {
            try {
                return Image(null, bitmap.toDrawable(Resources.getSystem()), false)
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        @JvmStatic
        private external fun nativeRender(
            bitmap: Bitmap,
            srcX: Int, srcY: Int, dst: Bitmap, dstX: Int, dstY: Int,
            width: Int, height: Int,
        )

        @JvmStatic
        private external fun nativeTexImage(
            bitmap: Bitmap,
            init: Boolean,
            offsetX: Int,
            offsetY: Int,
            width: Int,
            height: Int,
        )
    }
}
