@file:Suppress("MemberVisibilityCanBePrivate")

package app.revanced.patcher.apk

import app.revanced.patcher.Patcher
import app.revanced.patcher.apk.arsc.*
import app.revanced.patcher.apk.arsc.ArchiveBackend
import app.revanced.patcher.apk.arsc.ValuesBackend
import app.revanced.patcher.logging.Logger
import app.revanced.patcher.patch.PatchResult
import app.revanced.patcher.util.ProxyBackedClassList
import app.revanced.patcher.util.ensureValid
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkUtil
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.apk.xmlencoder.ValuesEncoder
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.archive.APKArchive
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.base.Block
import com.reandroid.arsc.base.BlockArray
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.util.FrameworkTable
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ResTableEntry
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


sealed class Apk private constructor(internal val module: ApkModule, internal val logger: Logger) {
    lateinit var path: String

    companion object {
        const val MANIFEST_NAME = "AndroidManifest.xml"
    }

    /**
     * The apk file that is to be patched.
     *
     * @param filePath The path to the apk file.
     */
    constructor(filePath: String, logger: Logger) : this(
        ApkModule.loadApkFile(
            File(filePath),
            ApkUtil.toModuleName(File(filePath))
        ), logger
    ) {
        path = filePath
    }

    init {
        module.setAPKLogger(logger)
    }

    override fun toString(): String = module.moduleName // TODO: revert this

    /**
     * A [ResourceMapper].
     */
    @Deprecated("will be removed.")
    internal val mapper = module.tableBlock?.pickOne()?.let { ResourceMapper(it) }

    /**
     * Encoding manager
     */
    // internal val encodeManager = EncodeManager(module, logger, mapper)

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
     */
    internal fun finalize() {
        openFiles.forEach { logger.warn("File $it was never closed! File modifications will not be applied if you do not close them.") }
        resources.finalize()

        // Update manifest and package block name.
        val updatedManifest =
            module.apkArchive.getInputSource(MANIFEST_NAME).openStream().use { AndroidManifestBlock.load(it) }
        module.setManifest(updatedManifest)
        module.androidManifestBlock.refresh()
        if (module.hasTableBlock()) {
            resources.packageBlock.name = updatedManifest.packageName
        }
    }

    /**
     * Open a [app.revanced.patcher.apk.File]
     */
    fun openFile(path: String): app.revanced.patcher.apk.File {
        val index = getTypeIndex(path)
        return File(
            path, this, when {
                path == "res/values/public.xml" -> throw ApkException.Encode("Editing the resource table is not supported.")
                path.startsWith("res/values") -> {
                    val (qualifiers, type) = index!!
                    ValuesBackend(
                        qualifiers,
                        type,
                        resources ?: throw ApkException.Encode("Cannot edit values without a resource table.")
                    )
                }

                path.endsWith(".xml") -> ArchiveBackend.XML(path, index, resources, module)
                else -> ArchiveBackend.Raw(path, index, module.apkArchive, resources)
            }
        )
    }

    /**
     * @param out The [File] to write to.
     */
    fun save(out: File) {
        module.writeApk(out)
    }

    /**
     * The split apk file that is to be patched.
     *
     * @param filePath The path to the apk file.
     * @see Apk
     */
    sealed class Split(filePath: String, logger: Logger) : Apk(filePath, logger) {

        /**
         * The split apk file which contains language files.
         *
         * @param filePath The path to the apk file.
         */
        class Language(filePath: String, logger: Logger) : Split(filePath, logger)

        /**
         * The split apk file which contains libraries.
         *
         * @param filePath The path to the apk file.
         */
        class Library(filePath: String, logger: Logger) : Split(filePath, logger)

        /**
         * The split apk file which contains assets.
         *
         * @param filePath The path to the apk file.
         */
        class Asset(filePath: String, logger: Logger) : Split(filePath, logger)
    }

    /**
     * The base apk file that is to be patched.
     *
     * @param filePath The path to the apk file.
     * @see Apk
     */
    class Base(filePath: String, logger: Logger) : Apk(filePath, logger) {
        /**
         * Data of the [Base] apk file.
         */
        internal val bytecodeData = BytecodeData()
    }

    internal inner class Resources(private val tableBlock: TableBlock) {
        val packageBlock: PackageBlock = tableBlock.pickOne()
        val entryStore = TableEntryStore().apply {
            if (tableBlock !is FrameworkTable) {
                tableBlock.frameWorks.forEach { add(it) }
            }
            add(tableBlock)
        }

        val encodeMaterials: EncodeMaterials = EncodeMaterials.create(tableBlock).apply { setAPKLogger(logger) }

        val valuesEncoder = ValuesEncoder(encodeMaterials)

        /**
         * A map of resource files from their actual name to their archive name.
         * Example: res/drawable-hdpi/icon.png -> res/4a.png
         */
        val resFileTable = module.listResFiles().associate { "res/${it.buildPath()}" to it.filePath }

        fun finalize() {
            // Scan for @+id registrations.
            module.apkArchive.listInputSources().forEach {
                if (it is XMLEncodeSource) {
                    it.xmlSource.xmlDocument.scanIdRegistrations().forEach { attr ->
                        val name = attr.value.split('/').last()
                        logger.trace("Registering ID: $name")
                        packageBlock.getOrCreate("", "id", name).also { entry ->
                            (entry.tableEntry as ResTableEntry).value.valueAsBoolean = false
                        }
                        attr.value = "@id/$name"
                    }
                }
            }
        }
    }

    internal val resources = Resources(module.tableBlock ?: frameworkTable)

    internal inner class BytecodeData {
        private val dexFile = MultiDexContainerBackedDexFile(object : MultiDexContainer<DexBackedDexFile> {
            /**
             * Load all dex files from the [ApkModule] and create an entry for each of them.
             */
            private val entries = module.listDexFiles().map {
                module.apkArchive.remove(it.name)
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
         * Write [classes] to the [APKArchive].
         *
         * @param archive The [APKArchive] to write to.
         */
        internal fun writeDexFiles(archive: APKArchive) {
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
                archive.add(ByteInputSource(ensureValid(store.buffer, store.size), name))
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
