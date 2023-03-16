package app.revanced.patcher.apk

import com.reandroid.apk.ApkModule
import com.reandroid.xml.XMLDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal class ManifestCoder(private val apk: Apk) : Coder {
    override fun decode(): ByteArray = ByteArrayOutputStream(1024).apply { apk.manifestXml.save(this, true) }.toByteArray()

    override fun encode(contents: ByteArray) {
        apk.manifestXml = XMLDocument.load(ByteArrayInputStream(contents))
    }

    override fun exists() = true
}