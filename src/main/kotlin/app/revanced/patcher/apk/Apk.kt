@file:Suppress("MemberVisibilityCanBePrivate")

package app.revanced.patcher.apk

import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.arsc.*
import app.revanced.patcher.arsc.ArchiveBackend
import app.revanced.patcher.arsc.LazyXMLInputSource
import app.revanced.patcher.arsc.scanIdRegistrations
import app.revanced.patcher.util.ProxyBackedClassList
import com.reandroid.apk.AndroidFrameworks
import com.reandroid.apk.ApkModule
import com.reandroid.apk.xmlencoder.EncodeException
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.archive.InputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.util.FrameworkTable
import com.reandroid.arsc.value.Entry
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

        resources.useMaterials {
            module.apkArchive.listInputSources().forEach {
                val pkg = resources.packageBlock
                if (it is LazyXMLInputSource) {
                    if (resources.hasResourceTable) {
                        // Scan for @+id registrations.
                        it.document.scanIdRegistrations().forEach { attr ->
                            val name = attr.value.split('/').last()
                            options.logger.trace("Registering ID: $name")
                            pkg!!.getOrCreate("", "id", name).resValue.valueAsBoolean = false
                            attr.value = "@id/$name"
                        }
                    }

                    try {
                        it.encode() // Encode the LazyXMLInputSource.
                    } catch (e: EncodeException) {
                        throw ApkException.Encode("Failed to encode ${it.name}", e)
                    }
                }

                if (it.name == AndroidManifestBlock.FILE_NAME) {
                    // Update package block name
                    val manifest = it.openStream().use { stream -> AndroidManifestBlock.load(stream) }
                    pkg?.name = manifest.packageName
                }
            }
        }
    }

    /**
     * Open a [app.revanced.patcher.apk.File]
     */
    // TODO: move the public part of this thing to Resources, leaving only a public function to open the manifest in its place.
    fun openFile(path: String) = File(
        path, this, when {
            resources.hasResourceTable && path.startsWith("res/values") -> throw Error("explode: $path")
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

    internal inner class Resources(val tableBlock: TableBlock?) {
        val hasResourceTable = module.hasTableBlock()

        val packageBlock: PackageBlock? =
            tableBlock?.packageArray?.let {
                if (it.childes.size == 1) it[0] else it.iterator()?.asSequence()
                    ?.single { it.name == module.packageName }
            }

        lateinit var global: ApkBundle.GlobalResources

        internal fun <R> useMaterials(callback: (EncodeMaterials) -> R): R {
            val materials = global.encodeMaterials
            val previous = materials.currentPackage
            if (packageBlock != null) {
                materials.currentPackage = packageBlock
            }

            return try {
                callback(materials)
            } finally {
                materials.currentPackage = previous
            }
        }

        /*
        val entryStore = TableEntryStore().apply {
            if (hasResourceTable) {
                tableBlock.frameWorks.forEach { add(it) }
            }
            add(tableBlock)
        }

        // TODO: make deez global
        val resourceIds = ResourceIds().takeIf { hasResourceTable }?.apply {
            loadPackageBlock(packageBlock)
        }
        val pkg = resourceIds?.table?.listPackages()?.get(0)
        val encodeMaterials = EncodeMaterials().apply {
            pkg?.let { addPackageIds(it) }
            if (tableBlock !is FrameworkTable) {
                currentPackage = packageBlock
                tableBlock.frameWorks.forEach {
                    if (it is FrameworkTable) addFramework(it)
                }
            } else {
                currentPackage = TableBlock().apply { packageArray.add(PackageBlock()) }.pickOne()
                addFramework(tableBlock)
            }

        }
         */
        internal fun <R> usePackageBlock(callback: (PackageBlock) -> R): R {
            if (packageBlock == null) {
                throw ApkException.Decode("Apk does not have a resource table")
            }
            return callback(packageBlock)
        }

        fun resolve(ref: String) = useMaterials { it.resolveReference(ref) }

        private fun Entry.setTo(value: Resource) {
            val specRef = specReference
            ensureComplex(value.complex)
            specReference = specRef
            value.write(this, this@Apk)
        }

        fun set(type: String, name: String, value: Resource, configuration: String? = null) =
            usePackageBlock { pkg -> pkg.getOrCreate(configuration, type, name).also { it.setTo(value) }.resourceId }

        fun setGroup(type: String, map: Map<String, Resource>, configuration: String? = null) {
            usePackageBlock { pkg ->
                pkg.getOrCreateSpecType(type).getOrCreateTypeBlock(configuration).apply {
                    map.forEach { (name, value) -> getOrCreateEntry(name).setTo(value) }
                }
            }
        }
    }

    @Deprecated("use Apk.resources instead lol")
    fun setResource(type: String, name: String, value: Resource, configuration: String? = null) = resources.set(type, name, value, configuration)
    @Deprecated("use Apk.resources instead lol")
    fun setResources(type: String, map: Map<String, Resource>, configuration: String? = null) = resources.setGroup(type, map, configuration)

    internal val resources = Resources(module.tableBlock)

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
