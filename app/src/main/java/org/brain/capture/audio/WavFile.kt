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
