package io.github.harryl0l.llamachat.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import io.github.harryl0l.llamachat.engine.VideoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Media converted into the raw RGB form libmtmd expects. */
sealed interface DecodedMedia {
    class Image(val rgb: ByteArray, val width: Int, val height: Int) : DecodedMedia
    class Video(
        val frames: ByteArray,      // nFrames * width * height * 3
        val width: Int,
        val height: Int,
        val timestamps: FloatArray, // seconds, one per frame
        val template: Int,          // mtmd_video_template
    ) : DecodedMedia
}

object MediaDecoder {
    private const val TAG = "MediaDecoder"

    /** Longest side for still images; mtmd resizes again for the model, this just bounds memory. */
    private const val MAX_IMAGE_SIDE = 1536

    /** Longest side for video frames when the model has no preferred frame size. */
    private const val DEFAULT_FRAME_SIDE = 448

    /** Default sampling rate when the model has no preference. */
    private const val DEFAULT_FPS = 1f

    // must match enum mtmd_video_template
    private const val TEMPLATE_DEFAULT = 0

    /**
     * Pixel budget for still images per quality level (0 = no extra cap, only [MAX_IMAGE_SIDE]).
     * Dynamic-resolution encoders (Qwen-VL…) turn more pixels into more tokens: on the test host a
     * 1536x1152 photo was 494 tokens / 4.0 s vs 192 tokens / 1.4 s at 512x384.
     */
    val IMAGE_QUALITY_PIXELS = intArrayOf(200_000, 450_000, 0)

    suspend fun decodeImage(context: Context, uri: Uri, maxPixels: Int = 0): DecodedMedia.Image = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            var scale = min(1f, MAX_IMAGE_SIDE.toFloat() / max(w, h))
            if (maxPixels > 0) scale = min(scale, kotlin.math.sqrt(maxPixels.toFloat() / (w.toFloat() * h)))
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }
        val rgb = bitmap.toRgb()
        DecodedMedia.Image(rgb, bitmap.width, bitmap.height).also { bitmap.recycle() }
    }

    /**
     * Samples frames from a video following the model's recommendation.
     * [videoConfig] is asked for the model's preferred layout given the source properties.
     *
     * Frames are decoded with MediaCodec (hardware, single forward pass, see [VideoFrameGrabber]).
     * If the device decoder can't hand out YUV images, falls back to MediaMetadataRetriever.
     */
    /** Pixel budget per frame for each video quality level (0 = no cap: the model's choice). */
    val VIDEO_QUALITY_PIXELS = intArrayOf(100_000, 200_000, 0)

    suspend fun decodeVideo(
        context: Context,
        uri: Uri,
        maxFrames: Int,
        maxPixels: Int,
        videoConfig: suspend (width: Int, height: Int, nFrames: Int, fps: Float) -> VideoConfig,
    ): DecodedMedia.Video = withContext(Dispatchers.IO) {
        // --- source properties
        val mmr = MediaMetadataRetriever()
        val rotation: Int
        var srcW: Int
        var srcH: Int
        val durationS: Float
        val srcFrames: Int
        try {
            mmr.setDataSource(context, uri)
            fun meta(key: Int) = mmr.extractMetadata(key)
            rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            srcW = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            srcH = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            durationS = (meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000f
            srcFrames = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: -1
        } finally {
            mmr.release()
        }
        if (rotation == 90 || rotation == 270) srcW = srcH.also { srcH = srcW }
        val srcFps = if (srcFrames > 0 && durationS > 0f) srcFrames / durationS else 30f
        require(srcW > 0 && srcH > 0 && durationS > 0f) { "Unsupported or corrupted video" }

        val cfg = videoConfig(srcW, srcH, srcFrames, srcFps)

        // --- which timestamps to sample
        var times: List<Float> = if (cfg.nFrames > 0) {
            // same as the reference processors: round(linspace(0, n_src - 1, n))
            val n = min(cfg.nFrames, maxFrames).coerceAtLeast(1)
            val total = if (srcFrames > 0) srcFrames else (durationS * srcFps).toInt()
            val step = if (n > 1) (total - 1).toDouble() / (n - 1) else 0.0
            List(n) { i -> ((i * step).roundToInt() / srcFps) }
        } else {
            val fps = if (cfg.fps > 0f) cfg.fps else DEFAULT_FPS
            val natural = (durationS * fps).toInt() + 1
            if (natural <= maxFrames) {
                List(natural) { i -> i / fps }
            } else {
                // too long: spread maxFrames evenly over the whole clip
                List(maxFrames) { i -> i * durationS / maxFrames }
            }
        }.map { it.coerceIn(0f, max(0f, durationS - 0.001f)) }
        // templates other than the default group frames in pairs: keep the count even
        if (cfg.template != TEMPLATE_DEFAULT && times.size % 2 == 1) times = times + times.last()

        // --- output frame size (after rotation)
        var (outW, outH) = if (cfg.width > 0 && cfg.height > 0) {
            cfg.width to cfg.height
        } else {
            val s = min(1f, DEFAULT_FRAME_SIDE.toFloat() / max(srcW, srcH))
            ((srcW * s).roundToInt().coerceAtLeast(2) and 1.inv()) to ((srcH * s).roundToInt().coerceAtLeast(2) and 1.inv())
        }
        // The vision encoder's cost grows faster than linearly with pixels, and models like
        // Qwen-VL ask for large frames for short clips (e.g. 672x1216 for a 15 s portrait video).
        // Cap the frame size; multiples of 32 match the patch grid of common encoders.
        if (maxPixels > 0 && outW.toLong() * outH > maxPixels) {
            val s = kotlin.math.sqrt(maxPixels.toDouble() / (outW.toLong() * outH))
            outW = ((outW * s / 32).roundToInt() * 32).coerceAtLeast(64)
            outH = ((outH * s / 32).roundToInt() * 32).coerceAtLeast(64)
        }

        val ts = times.toFloatArray()
        val out = ByteArray(outW * outH * 3 * ts.size)
        try {
            VideoFrameGrabber(context, uri).use { it.grab(ts, outW, outH, rotation, out) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodec decode failed, falling back to MediaMetadataRetriever", e)
            grabWithRetriever(context, uri, ts, outW, outH, out)
        }
        DecodedMedia.Video(out, outW, outH, ts, cfg.template)
    }

    private fun grabWithRetriever(context: Context, uri: Uri, ts: FloatArray, outW: Int, outH: Int, out: ByteArray) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val frameBytes = outW * outH * 3
            var last: Bitmap? = null
            ts.forEachIndexed { i, t ->
                val frame = mmr.getScaledFrameAtTime((t * 1_000_000L).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, outW, outH)
                    ?: last
                    ?: error("Could not decode video frame at %.2fs".format(t))
                val exact = if (frame.width == outW && frame.height == outH) frame
                else Bitmap.createScaledBitmap(frame, outW, outH, true)
                exact.toRgb(out, i * frameBytes)
                last = exact
            }
        } finally {
            mmr.release()
        }
    }

    // ------------------------------------------------------------------ persistence (chat history)

    /** Saves RGB pixels (at [offset]) as a JPEG. */
    fun saveJpeg(rgb: ByteArray, offset: Int, width: Int, height: Int, file: java.io.File) {
        val pixels = IntArray(width * height)
        var o = offset
        for (i in pixels.indices) {
            pixels[i] = (0xFF shl 24) or ((rgb[o].toInt() and 0xFF) shl 16) or
                ((rgb[o + 1].toInt() and 0xFF) shl 8) or (rgb[o + 2].toInt() and 0xFF)
            o += 3
        }
        val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
    }

    /** Loads a JPEG saved by [saveJpeg]. Returns the pixels as RGB and the size. */
    fun loadJpeg(file: java.io.File): Triple<ByteArray, Int, Int> {
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Missing ${file.name}")
        val rgb = bmp.toRgb()
        return Triple(rgb, bmp.width, bmp.height).also { bmp.recycle() }
    }

    private fun Bitmap.toRgb(dst: ByteArray = ByteArray(width * height * 3), offset: Int = 0): ByteArray {
        val src = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        var o = offset
        for (p in pixels) {
            dst[o++] = (p shr 16 and 0xFF).toByte()
            dst[o++] = (p shr 8 and 0xFF).toByte()
            dst[o++] = (p and 0xFF).toByte()
        }
        return dst
    }
}
