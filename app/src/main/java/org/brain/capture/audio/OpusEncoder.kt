package org.brain.capture.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile

/**
 * Converts a 16 kHz mono 16-bit WAV (from [WavWriter]) to Opus in an Ogg container using the
 * platform codec, so no extra libraries are needed. ~24 kbit/s is clear for speech and about a
 * tenth of the WAV size.
 */
object OpusEncoder {
    private const val RATE = 16_000
    private const val BITRATE = 24_000
    private const val TIMEOUT_US = 2_000L

    fun encode(wav: File, out: File) {
        out.delete()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            var track = -1
            val info = MediaCodec.BufferInfo()

            RandomAccessFile(wav, "r").use { input ->
                input.seek(44)
                val chunk = ByteArray(8_192)
                var samplesIn = 0L
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    // Feed every free input buffer without waiting.
                    var fed = false
                    while (!inputDone) {
                        val i = codec.dequeueInputBuffer(0)
                        if (i < 0) break
                        val buf = codec.getInputBuffer(i)!!
                        val n = input.read(chunk, 0, minOf(chunk.size, buf.capacity()))
                        val pts = samplesIn * 1_000_000L / RATE
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val even = n - n % 2
                            buf.clear(); buf.put(chunk, 0, even)
                            codec.queueInputBuffer(i, 0, even, pts, 0)
                            samplesIn += even / 2
                        }
                        fed = true
                    }
                    // Drain every encoded packet that is ready (Opus makes one per 20 ms of audio);
                    // wait briefly only when there was nothing to do.
                    var o = codec.dequeueOutputBuffer(info, if (fed) 0 else TIMEOUT_US)
                    while (o != MediaCodec.INFO_TRY_AGAIN_LATER && !outputDone) {
                        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                        } else if (o >= 0) {
                            val data = codec.getOutputBuffer(o)!!
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!config && info.size > 0 && track >= 0) {
                                data.position(info.offset); data.limit(info.offset + info.size)
                                muxer.writeSampleData(track, data, info)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        if (!outputDone) o = codec.dequeueOutputBuffer(info, 0)
                    }
                }
            }
            check(track >= 0) { "Opus encoder produced no output" }
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
        check(out.length() > 0) { "Opus file is empty" }
    }
}
