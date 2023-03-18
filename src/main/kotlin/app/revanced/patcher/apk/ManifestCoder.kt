package app.revanced.patcher.apk

import app.revanced.patcher.apk.arsc.EncodeManager
import com.reandroid.apk.ApkModule
import com.reandroid.xml.XMLDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal class ManifestCoder(private val store: EncodeManager) : Coder {
    override fun decode(): ByteArray =
        ByteArrayOutputStream(1024).apply { store.manifestXml.save(this, true) }.toByteArray()

    override fun encode(contents: ByteArray) {
        store.manifestXml = XMLDocument.load(ByteArrayInputStream(contents))
    }

    override fun exists() = true
}