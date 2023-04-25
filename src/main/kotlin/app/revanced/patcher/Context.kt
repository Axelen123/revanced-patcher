package app.revanced.patcher

import app.revanced.patcher.apk.Apk
import app.revanced.patcher.apk.ResourceFile
import app.revanced.patcher.patch.PatchResult
import app.revanced.patcher.util.method.MethodWalker
import org.jf.dexlib2.iface.Method
import org.w3c.dom.Document
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * A class containing the common elements of [BytecodeContext] and [ResourceContext].
 *
 *  @param options The [PatcherOptions] of the [Patcher].
 */
sealed class Context(protected val options: PatcherOptions) {
    /**
     * Resolve a resource id for the specified resource.
     *
     * @param type The type of the resource.
     * @param name The name of the resource.
     * @return The id of the resource.
     */
    fun resourceIdOf(type: String, name: String) = options.apkBundle.resources.resTable.getResourceId(type, name)?.toLong() ?: throw PatchResult.Error("Could not find resource \"${name}\" of type \"${type}\"")
}

/**
 * A context for the bytecode of an [Apk.Base] file.
 *
 * @param options The [PatcherOptions] of the [Patcher].
 */
class BytecodeContext internal constructor(options: PatcherOptions) : Context(options) {
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
class ResourceContext internal constructor(options: PatcherOptions) : Context(options) {
    /**
     * The current [ApkBundle].
     */
    val apkBundle = options.apkBundle

    /**
     * Open an [DomFileEditor] for a given DOM file.
     *
     * @param inputStream The input stream to read the DOM file from.
     * @return A [DomFileEditor] instance.
     */
    fun openEditor(inputStream: InputStream) = DomFileEditor(inputStream)
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
    private val onClose: (() -> Unit)? = null,
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
    internal constructor(file: ResourceFile) : this(
        file.inputStream(),
        lazy { file.outputStream() },
        { file.close() }) {
        // Increase the lock.
        locks.merge(file.toString(), 1, Integer::sum)
        filePath = file.toString()
    }

    /**
     * Closes the editor. Write backs and decreases the lock count.
     *
     * Will not write back to the file if the file is still locked.
     */
    override fun close() {
        if (closed) return

        inputStream.close()

        // FOR REVIEWERS: I am not entirely sure if the locking code is obsolete now. Where was it relied upon in the patches?

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
                onClose?.invoke()
                return
            }
        }

        onClose?.invoke()
        closed = true
    }

    private companion object {
        // Map of concurrent open files.
        val locks = mutableMapOf<String, Int>()
    }
}