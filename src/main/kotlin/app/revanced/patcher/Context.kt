package app.revanced.patcher

import app.revanced.patcher.apk.ARSCPath
import app.revanced.patcher.apk.Apk
import app.revanced.patcher.apk.ApkBundle
import app.revanced.patcher.apk.ApkBundleFileSystemProvider
import app.revanced.patcher.patch.PatchResult
import app.revanced.patcher.util.method.MethodWalker
import org.jf.dexlib2.iface.Method
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * A common interface to constrain [Context] to [BytecodeContext] and [ResourceContext].
 */

sealed interface Context

/**
 * A context for the bytecode of an [Apk.Base] file.
 *
 * @param options The [PatcherOptions] of the [Patcher].
 */
class BytecodeContext internal constructor(options: PatcherOptions) : Context {
    /**
     * The list of classes.
     */
    val classes = options.apkBundle.base.bytecodeData.classes

    /**
     * Create a [MethodWalker] instance for the current [BytecodeContext].
     *
     * @param startMethod The method to start at.
     * @return A [MethodWalker] instance.
     */
    fun toMethodWalker(startMethod: Method): MethodWalker {
        return MethodWalker(this, startMethod)
    }
}

/**
 * A context for [Apk] file resources.
 *
 * @param options The [PatcherOptions] of the [Patcher].
 */
class ResourceContext internal constructor(private val options: PatcherOptions) : Context {
    /**
     * The current [ApkBundle].
     */
    val apkBundle = options.apkBundle

    val fsProvider = ApkBundleFileSystemProvider(apkBundle)

    /**
     * Get a file from the resources from the [Apk].
     *
     * @param path The path of the resource file.
     * @return A [File] instance for the resource file or null if not found in any context.
     */
    fun getFile(
        path: String,
        vararg contexts: Apk? = arrayOf(apkBundle.base, apkBundle.split?.asset)
    ): File? = throw Error("getFile() is unsupported. Use getPath() instead.")

    /**
     * Get a path from the resources from the [Apk].
     *
     * @param path The path of the resource file.
     * @return A [Path] instance for the resource file or null if not found in any context.
     */
    fun getPath(
        path: String,
        vararg contexts: Apk? = arrayOf(apkBundle.base, apkBundle.split?.asset)
    ) = contexts.firstNotNullOfOrNull {
        fsProvider.getFileSystem(URI("apk://$it"))?.getPath(path)?.takeIf(ARSCPath::exists)
    }

    /**
     * Open an [DomFileEditor] for a given DOM file.
     *
     * @param inputStream The input stream to read the DOM file from.
     * @return A [DomFileEditor] instance.
     */
    fun openEditor(inputStream: InputStream) = DomFileEditor(inputStream)

    /**
     * Open an [DomFileEditor] for a given DOM file.
     *
     * @param path The path to the DOM file.
     * @return A [DomFileEditor] instance.
     */
    fun openEditor(path: String, vararg contexts: Apk? = arrayOf(apkBundle.base, apkBundle.split?.asset)) =
        DomFileEditor(getPath(path, *contexts) ?: throw PatchResult.Error("The file $path can not be found."))
}

/**
 * Wrapper for a file that can be edited as a dom document.
 *
 * This constructor does not check for locks to the file when writing.
 * Use the secondary constructor.
 *
 * @param inputStream the input stream to read the xml file from.
 * @param outputStream the output stream to write the xml file to. If null, the file will be read only.
 *
 */
class DomFileEditor internal constructor(
    private val inputStream: InputStream,
    private val outputStream: Lazy<OutputStream>? = null,
) : Closeable {
    // Path to the xml file to unlock the resource when closing the editor.
    private var filePath: String? = null
    private var closed: Boolean = false

    /**
     * The document of the xml file.
     */
    val file: Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(inputStream)
        .also(Document::normalize)


    // Lazily open an output stream.
    // This is required because when constructing a DomFileEditor the output stream is created along with the input stream, which is not allowed.
    // The workaround is to lazily create the output stream. This way it would be used after the input stream is closed, which happens in the constructor.
    internal constructor(path: Path) : this(path.inputStream(), lazy { path.outputStream() }) {
        // Increase the lock.
        locks.merge(path.toString(), 1, Integer::sum)
        filePath = path.toString()
    }

    /**
     * Closes the editor. Write backs and decreases the lock count.
     *
     * Will not write back to the file if the file is still locked.
     */
    override fun close() {
        if (closed) return

        inputStream.close()

        // If the output stream is not null, do not close it.
        outputStream?.let {
            // Prevent writing to same file, if it is being locked
            // isLocked will be false if the editor was created through a stream.
            val isLocked = filePath?.let { path ->
                val isLocked = locks[path]!! > 1
                // Decrease the lock count if the editor was opened for a file.
                locks.merge(path, -1, Integer::sum)
                isLocked
            } ?: false

            // If unlocked, write back to the file.
            if (!isLocked) {
                it.value.use { stream ->
                    val result = StreamResult(stream)
                    TransformerFactory.newInstance().newTransformer().transform(DOMSource(file), result)
                }

                it.value.close()
                return
            }
        }

        closed = true
    }

    private companion object {
        // Map of concurrent open files.
        val locks = mutableMapOf<String, Int>()
    }
}