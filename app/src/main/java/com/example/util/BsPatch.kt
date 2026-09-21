package com.example.util

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Pure Kotlin implementation of BSDiff / BSPatch.
 * Allows applying a differential binary patch to an old file (e.g. the currently installed APK)
 * to reconstruct the exact new file (the new APK).
 *
 * File header: "BSDIFF40" (8 bytes)
 * Followed by 3 64-bit integers (stored in little-endian with sign bit in high bit):
 *   1. control block length (compressed)
 *   2. diff block length (compressed)
 *   3. new file length
 */
object BsPatch {

    private const val HEADER_MAGIC = "BSDIFF40"

    /**
     * Applies a BSDiff patch file to [oldFile], writing the reconstructed output to [newFile].
     */
    @Throws(Exception::class)
    fun patch(oldFile: File, newFile: File, patchFile: File) {
        val oldBytes = oldFile.readBytes()
        val patchBytes = patchFile.readBytes()
        val newBytes = patch(oldBytes, patchBytes)
        FileOutputStream(newFile).use { it.write(newBytes) }
    }

    /**
     * In-memory patch implementation.
     */
    @Throws(Exception::class)
    fun patch(oldBuf: ByteArray, patchBuf: ByteArray): ByteArray {
        if (patchBuf.size < 32) {
            throw IllegalArgumentException("Patch is too small to be a valid BSDIFF file")
        }

        val magic = String(patchBuf, 0, 8, Charsets.US_ASCII)
        if (magic != HEADER_MAGIC) {
            throw IllegalArgumentException("Invalid BSDIFF magic header: $magic")
        }

        val ctrlLen = readLong(patchBuf, 8)
        val diffLen = readLong(patchBuf, 16)
        val newSize = readLong(patchBuf, 24)

        if (ctrlLen < 0 || diffLen < 0 || newSize < 0 || newSize > Int.MAX_VALUE) {
            throw IllegalArgumentException("Corrupt BSDIFF header values")
        }

        val offsetCtrl = 32
        val offsetDiff = offsetCtrl + ctrlLen.toInt()
        val offsetExtra = offsetDiff + diffLen.toInt()

        if (offsetExtra > patchBuf.size) {
            throw IllegalArgumentException("Patch data truncated")
        }

        val ctrlStream = DataInputStream(GZIPInputStream(ByteArrayInputStream(patchBuf, offsetCtrl, ctrlLen.toInt())))
        val diffStream = GZIPInputStream(ByteArrayInputStream(patchBuf, offsetDiff, diffLen.toInt()))
        val extraStream = GZIPInputStream(ByteArrayInputStream(patchBuf, offsetExtra, patchBuf.size - offsetExtra))

        val newBuf = ByteArray(newSize.toInt())
        val oldSize = oldBuf.size

        var oldPos = 0
        var newPos = 0

        while (newPos < newSize) {
            // Read control tuple: (diffBytesCount, extraBytesCount, oldOffsetJump)
            val diffBytesCount = readLong(ctrlStream)
            val extraBytesCount = readLong(ctrlStream)
            val oldOffsetJump = readLong(ctrlStream)

            if (newPos + diffBytesCount > newSize) {
                throw IllegalStateException("Corrupt patch: diff exceeds new buffer size")
            }

            // 1. Read diff block and add to oldBuf
            readFully(diffStream, newBuf, newPos, diffBytesCount.toInt())
            for (i in 0 until diffBytesCount.toInt()) {
                val oldIdx = oldPos + i
                if (oldIdx in 0 until oldSize) {
                    newBuf[newPos + i] = (newBuf[newPos + i] + oldBuf[oldIdx]).toByte()
                }
            }

            newPos += diffBytesCount.toInt()
            oldPos += diffBytesCount.toInt()

            // 2. Read extra block (directly copies new bytes)
            if (newPos + extraBytesCount > newSize) {
                throw IllegalStateException("Corrupt patch: extra exceeds new buffer size")
            }

            readFully(extraStream, newBuf, newPos, extraBytesCount.toInt())
            newPos += extraBytesCount.toInt()
            oldPos += oldOffsetJump.toInt()
        }

        ctrlStream.close()
        diffStream.close()
        extraStream.close()

        return newBuf
    }

    private fun readFully(stream: InputStream, b: ByteArray, off: Int, len: Int) {
        var n = 0
        while (n < len) {
            val count = stream.read(b, off + n, len - n)
            if (count < 0) {
                throw java.io.EOFException("Unexpected end of patch stream")
            }
            n += count
        }
    }

    private fun readLong(buf: ByteArray, offset: Int): Long {
        var y: Long = 0
        for (i in 7 downTo 0) {
            y = (y shl 8) or ((buf[offset + i].toInt() and 0xFF).toLong())
        }
        if ((y and (1L shl 63)) != 0L) {
            y = -(y and (1L shl 63).inv())
        }
        return y
    }

    private fun readLong(stream: InputStream): Long {
        val b = ByteArray(8)
        readFully(stream, b, 0, 8)
        return readLong(b, 0)
    }
}
