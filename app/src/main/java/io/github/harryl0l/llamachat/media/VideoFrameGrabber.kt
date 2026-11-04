package io.github.harryl0l.llamachat.media

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Hardware video decoding with MediaExtractor + MediaCodec.
 *
 * Frames are decoded straight into YUV_420_888 [Image]s and only the frames we sample are
 * converted to RGB (with scaling + rotation fused in the same pass). Nothing is ever encoded.
 *
 * For dense sampling the decoder simply runs forward; when the next wanted frame is far away
 * it seeks to the previous sync frame instead of decoding everything in between.
 */
internal class VideoFrameGrabber(context: Context, uri: Uri) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val track: Int
    val format: MediaFormat

    init {
        extractor.setDataSource(context, uri, null)
        track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("No video track found")
        extractor.selectTrack(track)
        format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        codec = MediaCodec.createDecoderByType(mime)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /**
     * Decodes the frames closest to [timesS] (ascending, seconds) and writes each one as
     * RGB [outW]x[outH] into [dst] at index * outW * outH * 3. [rotation] is 0/90/180/270.
     */
    suspend fun grab(timesS: FloatArray, outW: Int, outH: Int, rotation: Int, dst: ByteArray) {
        val frameBytes = outW * outH * 3
        val info = MediaCodec.BufferInfo()
        var next = 0                 // index of the next wanted frame
        var inputDone = false
        var lastPts = Long.MIN_VALUE // pts of the last decoded frame
        var haveLast = false

        fun seekTo(us: Long) {
            extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush()
            inputDone = false
        }
        seekTo((timesS[0] * 1e6).toLong())

        var stalls = 0
        while (next < timesS.size) {
            currentCoroutineContext().ensureActive()

            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx < 0) {
                if (++stalls > 2000) error("Video decoder stalled")
                continue
            }
            stalls = 0
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val pts = info.presentationTimeUs

            if (info.size > 0) {
                val wantUs = (timesS[next] * 1e6).toLong()
                if (pts >= wantUs) {
                    // this frame covers every wanted time up to its pts
                    val image = codec.getOutputImage(outIdx) ?: error("Decoder gave no image")
                    image.use {
                        yuvToRgb(it, outW, outH, rotation, dst, next * frameBytes)
                    }
                    next++
                    while (next < timesS.size && (timesS[next] * 1e6).toLong() <= pts) {
                        System.arraycopy(dst, (next - 1) * frameBytes, dst, next * frameBytes, frameBytes)
                        next++
                    }
                    haveLast = true
                }
                lastPts = pts
            }
            codec.releaseOutputBuffer(outIdx, false)

            if (eos) {
                // past the last frame: repeat the last one for remaining timestamps
                while (next < timesS.size) {
                    if (!haveLast) error("Could not decode any video frame")
                    System.arraycopy(dst, (next - 1) * frameBytes, dst, next * frameBytes, frameBytes)
                    next++
                }
                break
            }

            // jump ahead when the next wanted frame is far away (> 2 s)
            if (next < timesS.size && !inputDone) {
                val wantUs = (timesS[next] * 1e6).toLong()
                if (wantUs - lastPts > 2_000_000L && wantUs - extractor.sampleTime > 2_000_000L) {
                    seekTo(wantUs)
                }
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }

    companion object {
        /**
         * YUV_420_888 -> RGB, nearest-neighbour scaled to [outW]x[outH] and rotated clockwise by
         * [rotation] degrees (output size is the size *after* rotation).
         */
        fun yuvToRgb(image: Image, outW: Int, outH: Int, rotation: Int, dst: ByteArray, offset: Int) {
            require(image.format == ImageFormat.YUV_420_888) { "Unexpected decoder output format ${image.format}" }
            val crop = image.cropRect
            val srcW = crop.width()
            val srcH = crop.height()
            val y = image.planes[0]
            val u = image.planes[1]
            val v = image.planes[2]
            val yBuf = y.buffer; val uBuf = u.buffer; val vBuf = v.buffer
            val yRow = y.rowStride; val yPix = y.pixelStride
            val uRow = u.rowStride; val uPix = u.pixelStride
            val vRow = v.rowStride; val vPix = v.pixelStride
            // size of the un-rotated picture as seen in the output
            val rotW = if (rotation == 90 || rotation == 270) outH else outW
            val rotH = if (rotation == 90 || rotation == 270) outW else outH

            var o = offset
            for (oy in 0 until outH) {
                for (ox in 0 until outW) {
                    // map output pixel back to the un-rotated picture
                    val (rx, ry) = when (rotation) {
                        90 -> oy to (rotH - 1 - ox)
                        180 -> (rotW - 1 - ox) to (rotH - 1 - oy)
                        270 -> (rotW - 1 - oy) to ox
                        else -> ox to oy
                    }
                    val sx = crop.left + rx * srcW / rotW
                    val sy = crop.top + ry * srcH / rotH
                    val yy = (yBuf.get(sy * yRow + sx * yPix).toInt() and 0xFF) - 16
                    val cx = sx shr 1
                    val cy = sy shr 1
                    val uu = (uBuf.get(cy * uRow + cx * uPix).toInt() and 0xFF) - 128
                    val vv = (vBuf.get(cy * vRow + cx * vPix).toInt() and 0xFF) - 128
                    // BT.601 limited range
                    val c = 1192 * yy.coerceAtLeast(0)
                    dst[o++] = ((c + 1634 * vv) shr 10).coerceIn(0, 255).toByte()
                    dst[o++] = ((c - 833 * vv - 400 * uu) shr 10).coerceIn(0, 255).toByte()
                    dst[o++] = ((c + 2066 * uu) shr 10).coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
