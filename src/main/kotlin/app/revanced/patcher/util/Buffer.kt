package app.revanced.patcher.util

import java.io.ByteArrayOutputStream

/**
 * Ensures the contents of a buffer is valid.
 * @param buffer A [ByteArray] with contents that may or may not be valid.
 * @param validBytes The number of valid bytes in the buffer.
 * @return A [ByteArray] containing the valid parts of the buffer.
 */
fun ensureValid(buffer: ByteArray, validBytes: Int) = if (buffer.size > validBytes) buffer.copyOf(validBytes) else buffer

/**
 * A [ByteArrayOutputStream] that avoids copying unless necessary.
 */
open class SmartByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
    fun data() = ensureValid(buf, count)
}