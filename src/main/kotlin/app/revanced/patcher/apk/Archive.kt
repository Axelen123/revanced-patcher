package app.revanced.patcher.apk

import app.revanced.patcher.util.xml.LazyXMLInputSource
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.InputSource
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.XMLException
import java.io.IOException

/**
 * @param virtualPath The resource file path (res/drawable-hdpi/icon.png)
 * @param archivePath The actual file path in the archive (res/4a.png)
 * @param close An action to perform when the file associated with this handle is closed
 */
internal data class FileHandle(val virtualPath: String, val archivePath: String, val close: () -> Unit)

private fun isResXml(inputSource: InputSource) = inputSource.openStream().use { ResXmlDocument.isResXmlBlock(it) }

internal class Archive(private val module: ApkModule) {
    class ReadResult(val xml: Boolean, val data: ByteArray)

    private val archive = module.apkArchive

    internal val openFiles = mutableSetOf<String>()

    internal fun lock(handle: FileHandle) {
        val path = handle.archivePath
        if (openFiles.contains(path)) {
            throw Apk.ApkException.Decode("${handle.virtualPath} is locked. If you are a patch developer, make sure you always close files.")
        }
        openFiles.add(path)
    }

    internal fun unlock(handle: FileHandle) {
        openFiles.remove(handle.archivePath)
    }

    fun read(resources: Apk.Resources, handle: FileHandle) =
        archive.getInputSource(handle.archivePath)?.let { inputSource ->
            try {
                val xml = when {
                    inputSource is LazyXMLInputSource -> inputSource.document
                    isResXml(inputSource) -> module.loadResXmlDocument(
                        inputSource
                    ).decodeToXml(resources.global.entryStore, resources.packageBlock?.id ?: 0)

                    else -> null
                }

                ReadResult(
                    xml != null,
                    xml?.toText()?.toByteArray() ?: inputSource.openStream().use { it.readAllBytes() })
            } catch (e: XMLException) {
                throw Apk.ApkException.Decode("Failed to decode XML while reading ${handle.virtualPath}", e)
            } catch (e: IOException) {
                throw Apk.ApkException.Decode("Could not read ${handle.virtualPath}", e)
            }
        }

    fun writeRaw(path: String, content: ByteArray) = archive.add(ByteInputSource(content, path))
    fun writeXml(resources: Apk.Resources, path: String, document: XMLDocument) = archive.add(
        LazyXMLInputSource(
            path,
            document,
            resources.global.encodeMaterials
        )
    )
}