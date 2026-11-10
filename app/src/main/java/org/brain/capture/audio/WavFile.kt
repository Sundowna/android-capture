package org.brain.capture.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-bit mono PCM WAV writer. The header is rewritten regularly, so a recording cut off by a crash
 * or a killed process is still a valid, playable file up to the last update.
 */
class WavWriter(file: File, private val sampleRate: Int = 16_000) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        raf.write(header(0))
    }

    fun write(samples: ShortArray, count: Int) {
        val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buf.putShort(samples[i])
        raf.write(buf.array())
        dataBytes += count * 2
    }

    /** Make the file on disk consistent: correct sizes in the header, data flushed. */
    fun sync() {
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.seek(pos)
        raf.fd.sync()
    }

    override fun close() {
        sync()
        raf.close()
    }

    private fun header(data: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((36 + data).toInt())
        put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
        putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(data.toInt())
    }.array()
}

object WavReader {
    /** Loudness profile of a recording, computed while streaming through the file. */
    class Profile(val samples: Long, val energy: DoubleArray, val mutedFraction: Double)

    /**
     * One pass over the file: RMS per 30 ms frame (for finding pauses) and the share of 100 ms
     * stretches that are exact digital silence (a muted microphone). Memory stays small.
     */
    fun profile(file: File, frame: Int = 480): Profile {
        val total = ((file.length() - 44) / 2).coerceAtLeast(0)
        val energy = DoubleArray((total / frame).toInt())
        var muted = 0; var tenths = 0
        java.io.BufferedInputStream(file.inputStream(), 1 shl 16).use { input ->
            input.skip(44)
            val buf = ByteArray(frame * 2)
            var f = 0; var zeroRun = 0; var inTenth = 0; var tenthAllZero = true
            while (f < energy.size) {
                var read = 0
                while (read < buf.size) { val n = input.read(buf, read, buf.size - read); if (n < 0) break; read += n }
                if (read < buf.size) break
                var sum = 0.0
                for (i in 0 until frame) {
                    val v = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xff)).toShort()
                    val x = v / 32768.0
                    sum += x * x
                    if (v.toInt() != 0) tenthAllZero = false
                    if (++inTenth == 1600) { tenths++; if (tenthAllZero) muted++; inTenth = 0; tenthAllZero = true }
                }
                energy[f++] = kotlin.math.sqrt(sum / frame)
            }
        }
        return Profile(total, energy, if (tenths == 0) 1.0 else muted / tenths.toDouble())
    }

    /** Samples [from, to) as floats in [-1, 1]. */
    fun readRange(file: File, from: Long, to: Long): FloatArray {
        val n = (to - from).toInt()
        val bytes = ByteArray(n * 2)
        RandomAccessFile(file, "r").use { it.seek(44 + from * 2); it.readFully(bytes) }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { bb.short / 32768f }
    }

    /** Reads a 16-bit mono WAV written by [WavWriter] into floats in [-1, 1]. */
    fun readFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "Recording is empty" }
        // Trust the actual file length over the header: after a crash the header may lag behind.
        val count = (bytes.size - 44) / 2
        val bb = ByteBuffer.wrap(bytes, 44, count * 2).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { bb.short / 32768f }
    }
}
