@file:Suppress("MemberVisibilityCanBePrivate")

package app.revanced.patcher.apk

import app.revanced.patcher.DomFileEditor
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.arsc.*
import app.revanced.patcher.arsc.ResourceFileImpl
import app.revanced.patcher.arsc.LazyXMLInputSource
import app.revanced.patcher.arsc.scanIdRegistrations
import app.revanced.patcher.util.ProxyBackedClassList
import com.reandroid.apk.ApkModule
import com.reandroid.apk.xmlencoder.EncodeException
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.apk.xmlencoder.EncodeUtil
import com.reandroid.archive.InputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResConfig
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
sealed class Apk private constructor(internal val module: ApkModule) {
    companion object {
        const val manifest = "AndroidManifest.xml"

        private fun ApkModule.isFeatureModule() = androidManifestBlock.manifestElement.let {
            it.searchAttributeByName("isFeatureSplit")?.valueAsBoolean == true || it.searchAttributeByName("configForSplit") != null
        }

        fun new(path: File) = ApkModule.loadApkFile(path).let { module ->
            when {
                module.isBaseModule -> Base(module)
                module.isFeatureModule() -> FeatureModule(module)
                else -> Split(module)
            }
        }
    }

    /**
     * The metadata of the [Apk].
     */
    val packageMetadata = PackageMetadata(module.androidManifestBlock)

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
    internal open fun finalize(options: PatcherOptions) {
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

                if (it.name == manifest) {
                    // Update package block name
                    resources.packageBlock?.name =
                        it.openStream().use { stream -> AndroidManifestBlock.load(stream) }.packageName
                }
            }
        }
    }

    inner class Resources(val tableBlock: TableBlock?) {
        internal val hasResourceTable = module.hasTableBlock()

        internal val packageBlock: PackageBlock? =
            tableBlock?.packageArray?.let { array ->
                if (array.childes.size == 1) array[0] else array.iterator()?.asSequence()
                    ?.single { it.name == module.packageName }
            }

        internal lateinit var global: ApkBundle.GlobalResources

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

        internal fun <R> usePackageBlock(callback: (PackageBlock) -> R): R {
            if (packageBlock == null) {
                throw ApkException.MissingResourceTable
            }
            return callback(packageBlock)
        }

        internal fun resolve(ref: String) = try {
            useMaterials { it.resolveReference(ref) }
        } catch (e: EncodeException) {
            throw ApkException.ReferenceError(ref, e)
        }

        private fun Entry.setTo(value: Resource) {
            val specRef = specReference
            ensureComplex(value.complex)
            specReference = specRef
            value.write(this, this@Resources)
        }

        private fun getEntry(type: String, name: String, qualifiers: String?) =
            global.resTable.getResourceId(type, name)?.let { id ->
                val config = ResConfig.parse(qualifiers)
                tableBlock?.resolveReference(id)?.singleOrNull { it.resConfig == config }
            }

        private fun getBackend(resPath: String): ResourceFileImpl {
            if (resPath.startsWith("res/values")) throw ApkException.Decode("Decoding the resource table as a file is not supported")

            var callback: (() -> Unit)? = null
            var archivePath = resPath

            if (tableBlock != null && resPath.startsWith("res/") && resPath.count { it == '/' } == 2) {
                val file = File(resPath)

                val qualifiers = EncodeUtil.getQualifiersFromResFile(file)
                val type = EncodeUtil.getTypeNameFromResFile(file)
                val name = file.nameWithoutExtension

                // The resource file names that app developers use might not be kept in the archive, so we have to resolve it with the resource table.
                // Example: res/drawable-hdpi/icon.png -> res/4a.png
                val resolvedPath = getEntry(type, name, qualifiers)?.resValue?.valueAsString

                if (resolvedPath != null) {
                    archivePath = resolvedPath
                } else {
                    // An entry for this specific resource file was not found in the resource table, so we have to register it after we save.
                    callback = { set(type, name, StringResource(archivePath), qualifiers) }
                }
            }

            return if (resPath.endsWith(".xml")) ResourceFileImpl.XML(
                archivePath,
                this,
                module,
                callback
            ) else ResourceFileImpl.Raw(archivePath, module.apkArchive, callback)
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

        /**
         * Open a [app.revanced.patcher.apk.ResourceFile]
         */
        fun openFile(path: String) = ResourceFile(
            path, this@Apk, resources.getBackend(path)
        )

        fun openEditor(path: String) = DomFileEditor(openFile(path))
    }

    val resources = Resources(module.tableBlock)

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
     *
     * @param packageName The package name of the [Apk] file.
     * @param packageVersion The package version of the [Apk] file.
     */
    data class PackageMetadata(val packageName: String, val packageVersion: String) {
        internal constructor(manifestBlock: AndroidManifestBlock) : this(
            manifestBlock.packageName ?: "unnamed split apk file", manifestBlock.versionName ?: "0.0.0"
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
     * @param path The path to the apk file.
     * @see Apk
     */
    class Split(module: ApkModule) : Apk(module) {
        enum class Type {
            LANGUAGE, LIBRARY, ASSET;
        }

        private companion object {
            val architectures = setOf("armeabi_v7a", "arm64_v8a", "x86", "x86_64")
        }

        val config = module.split.removePrefix("config.")
        val type: Type = run {
            if (config.length == 2) {
                return@run Type.LANGUAGE
            }
            if (architectures.contains(config)) {
                return@run Type.LIBRARY
            }

            val density = ResConfig.Density.valueOf(config)
            if (density != null) {
                return@run Type.ASSET
            }
            throw Error("Cannot figure out the split type of: $config")
        }

        override fun toString() = "split_config.$config.apk"
    }

    /**
     * The base apk file that is to be patched.
     *
     * @param path The path to the apk file.
     * @see Apk
     */
    class Base(module: ApkModule) : Apk(module) {
        /**
         * Data of the [Base] apk file.
         */
        internal val bytecodeData = BytecodeData()

        override fun toString() = "base.apk"

        override fun finalize(options: PatcherOptions) {
            super.finalize(options)

            options.logger.info("Writing patched dex files")
            bytecodeData.writeDexFiles()
        }
    }

    class FeatureModule(module: ApkModule) : Apk(module)

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

        class ReferenceError(ref: String, throwable: Throwable? = null) :
            ApkException("Failed to resolve: $ref", throwable) {
            constructor(type: String, name: String, throwable: Throwable? = null) : this("@$type/$name", throwable)
        }

        object MissingResourceTable : ApkException("Apk does not have a resource table.")
    }
}
