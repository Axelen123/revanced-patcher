package app.revanced.patcher.util

import java.io.ByteArrayOutputStream

/**
 * A [ByteArrayOutputStream] that avoids copying unless necessary.
 */
open class SmartByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
    fun data() = if (buf.size > count) buf.copyOf(count) else buf
}