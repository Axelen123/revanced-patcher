@file:Suppress("MemberVisibilityCanBePrivate")

package app.revanced.patcher.apk

import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.apk.arsc.*
import app.revanced.patcher.apk.arsc.ArchiveBackend
import app.revanced.patcher.apk.arsc.ValuesBackend
import app.revanced.patcher.util.ProxyBackedClassList
import com.reandroid.apk.AndroidFrameworks
import com.reandroid.apk.ApkModule
import com.reandroid.apk.xmlencoder.EncodeException
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.apk.xmlencoder.ValuesEncoder
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.archive.InputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.util.FrameworkTable
import com.reandroid.common.TableEntryStore
import lanchon.multidexlib2.BasicDexEntry
import lanchon.multidexlib2.DexIO
import lanchon.multidexlib2.MultiDexContainerBackedDexFile
import lanchon.multidexlib2.MultiDexIO
import lanchon.multidexlib2.RawDexIO
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.MultiDexContainer
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.w3c.dom.*
import java.io.File
import java.util.zip.ZipEntry



/**
 * The [Apk] file that is to be patched.
 *
 * @param path The path to the apk file.
 * @param name The name of this apk.
 */
sealed class Apk private constructor(val path: File, name: String) {
    companion object {
        val frameworkTable: FrameworkTable = AndroidFrameworks.getLatest().tableBlock
    }

    internal val module = ApkModule.loadApkFile(path, name)


    override fun toString(): String = module.moduleName

    /**
     * A [ResourceMapper].
     */
    @Deprecated("will be removed.")
    internal val mapper = module.tableBlock?.pickOne()?.let { ResourceMapper(it) }

    /**
     * The metadata of the [Apk].
     */
    val packageMetadata = PackageMetadata().also {
        val manifest = module.androidManifestBlock
        if (manifest.versionName != null) {
            it.packageName = manifest.packageName
            it.packageVersion = manifest.versionName
        }
    }

    private val openFiles = mutableSetOf<String>()

    internal fun lockFile(path: String) {
        if (openFiles.contains(path)) {
            throw ApkException.Decode("Path \"$path\" is locked. If you are a patch developer, make sure you always close files.")
        }
        openFiles.add(path)
    }

    internal fun unlockFile(path: String) {
        openFiles.remove(path)
    }

    /**
     * Refresh updated resources for an [Apk].
     *
     * @param options The [PatcherOptions] of the [Patcher].
     */
    internal fun finalize(options: PatcherOptions) {
        openFiles.forEach { options.logger.warn("File $it was never closed! File modifications will not be applied if you do not close them.") }

        module.apkArchive.listInputSources().forEach {
            if (it is XMLEncodeSource) {
                if (resources.hasResourceTable) {
                    // Scan for @+id registrations.
                    it.xmlSource.xmlDocument.scanIdRegistrations().forEach { attr ->
                        val name = attr.value.split('/').last()
                        options.logger.trace("Registering ID: $name")
                        resources.packageBlock.getOrCreate("", "id", name).value { res ->
                            res.valueAsBoolean = false
                        }
                        attr.value = "@id/$name"
                    }
                }

                try {
                    it.resXmlBlock // Force the XMLEncodeSource to encode.
                } catch (e: EncodeException) {
                    throw ApkException.Encode("Failed to encode ${it.name}", e)
                }
            }

            if (it.name == AndroidManifestBlock.FILE_NAME) {
                // Update package block name
                val manifest = it.openStream().use { stream -> AndroidManifestBlock.load(stream) }
                if (resources.hasResourceTable) {
                    resources.packageBlock.name = manifest.packageName
                }
            }
        }
    }

    /**
     * Open a [app.revanced.patcher.apk.File]
     */
    fun openFile(path: String) = File(
        path, this, when {
            resources.hasResourceTable && path.startsWith("res/values") -> ValuesBackend(
                File(path),
                resources
            )

            path.endsWith(".xml") -> ArchiveBackend.XML(path, resources, module)
            else -> ArchiveBackend.Raw(path, resources, module.apkArchive)
        }
    )

    /**
     * @param out The [File] to write to.
     */
    fun save(out: File) {
        module.writeApk(out)
    }

    /**
     * The split apk file that is to be patched.
     *
     * @param path The path to the apk file.
     * @see Apk
     */
    sealed class Split(path: File, name: String) : Apk(path, name) {

        /**
         * The split apk file which contains language files.
         *
         * @param path The path to the apk file.
         */
        class Language(path: File) : Split(path, "language")

        /**
         * The split apk file which contains libraries.
         *
         * @param path The path to the apk file.
         */
        class Library(path: File) : Split(path, "library")

        /**
         * The split apk file which contains assets.
         *
         * @param path The path to the apk file.
         */
        class Asset(path: File) : Split(path, "asset")
    }

    /**
     * The base apk file that is to be patched.
     *
     * @param path The path to the apk file.
     * @see Apk
     */
    class Base(path: File) : Apk(path, "base") {
        /**
         * Data of the [Base] apk file.
         */
        internal val bytecodeData = BytecodeData()
    }

    internal inner class Resources(val tableBlock: TableBlock) {
        val hasResourceTable = module.hasTableBlock()

        val packageBlock: PackageBlock = tableBlock.pickOne()
        val entryStore = TableEntryStore().apply {
            if (hasResourceTable) {
                tableBlock.frameWorks.forEach { add(it) }
            }
            add(tableBlock)
        }
        val encodeMaterials: EncodeMaterials = EncodeMaterials.create(tableBlock)
        val valuesEncoder = ValuesEncoder(encodeMaterials)
    }

    internal val resources = Resources(module.tableBlock ?: frameworkTable)

    internal inner class BytecodeData {
        private val dexFile = MultiDexContainerBackedDexFile(object : MultiDexContainer<DexBackedDexFile> {
            /**
             * Load all dex files from the [ApkModule] and create an entry for each of them.
             */
            private val entries = module.listDexFiles().map {
                BasicDexEntry(
                    this,
                    it.name,
                    it.openStream().use { stream -> RawDexIO.readRawDexFile(stream, it.length, null) })
            }.associateBy { it.entryName }

            override fun getDexEntryNames() = entries.keys.toList()
            override fun getEntry(entryName: String) = entries[entryName]
        })
        private val opcodes = dexFile.opcodes

        /**
         * The classes and proxied classes of the [Base] apk file.
         */
        val classes = ProxyBackedClassList(dexFile.classes)

        /**
         * Write [classes] to the archive.
         *
         */
        internal fun writeDexFiles() {
            // Make sure to replace all classes with their proxy.
            val classes = classes.also(ProxyBackedClassList::applyProxies)
            val opcodes = opcodes

            // Create patched dex files.
            mutableMapOf<String, MemoryDataStore>().also {
                val newDexFile = object : org.jf.dexlib2.iface.DexFile {
                    override fun getClasses() = classes.toSet()
                    override fun getOpcodes() = opcodes
                }

                // Write modified dex files.
                MultiDexIO.writeDexFile(
                    true, -1, // Core count.
                    it, Patcher.dexFileNamer, newDexFile, DexIO.DEFAULT_MAX_DEX_POOL_SIZE, null
                )
            }.forEach { (name, store) ->
                module.apkArchive.add(object : InputSource(name) {
                    override fun getMethod() = ZipEntry.DEFLATED
                    override fun getLength(): Long = store.size.toLong()
                    override fun openStream() = store.readAt(0)
                })
            }
        }
    }

    /**
     * Metadata about an [Apk] file.
     */
    class PackageMetadata {
        /**
         * The package name of the [Apk] file.
         */
        var packageName: String = "unnamed split apk file"
            internal set

        /**
         * The package version of the [Apk] file.
         */
        var packageVersion: String = "0.0.0"
            internal set
    }

    /**
     * An exception thrown in [h].
     *
     * @param message The exception message.
     * @param throwable The corresponding [Throwable].
     */
    sealed class ApkException(message: String, throwable: Throwable? = null) : Exception(message, throwable) {
        /**
         * An exception when decoding resources.
         *
         * @param message The exception message.
         * @param throwable The corresponding [Throwable].
         */
        class Decode(message: String, throwable: Throwable? = null) : ApkException(message, throwable)

        /**
         * An exception when encoding resources.
         *
         * @param message The exception message.
         * @param throwable The corresponding [Throwable].
         */
        class Encode(message: String, throwable: Throwable? = null) : ApkException(message, throwable)
    }
}
