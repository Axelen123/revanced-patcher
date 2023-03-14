@file:Suppress("MemberVisibilityCanBePrivate")

package app.revanced.patcher.apk

import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.logging.Logger
import app.revanced.patcher.util.ProxyBackedClassList
import com.reandroid.apk.*
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.apk.xmlencoder.ValuesEncoder
import com.reandroid.archive.APKArchive
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.ZipAlign
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.common.Frameworks
import com.reandroid.common.TableEntryStore
import com.reandroid.xml.XMLDocument

import lanchon.multidexlib2.DexIO
import lanchon.multidexlib2.MultiDexIO
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.w3c.dom.*
import java.io.File

/**
 * The apk file that is to be patched.
 *
 * @param filePath The path to the apk file.
 */
sealed class Apk(filePath: String, internal val logger: Logger) {
    /**
     * The apk file.
     */
    open val file = File(filePath)

    /**
     * ARSCLib Apk module.
     */
    internal var module = ApkModule.loadApkFile(file, ApkUtil.toModuleName(file)).also { it.setAPKLogger(logger) }

    internal val tableBlock: TableBlock? = module.tableBlock
    internal val packageBlock = tableBlock?.packageArray?.pickOne()

    /**
     * EntryStore for decoding.
     */
    internal val entryStore = tableBlock?.let {
        TableEntryStore().apply {
            add(Frameworks.getAndroid())
            add(it)
        }
    }

    /**
     * EncodeMaterials for encoding.
     * TODO: subclass EncodeMaterials and eagerly register resources that do not exist yet.
     */
    internal val encodeMaterials = tableBlock?.let { EncodeMaterials.create(it).apply { setAPKLogger(logger) } }

    internal val valuesEncoder = encodeMaterials?.let { ValuesEncoder(encodeMaterials) }

    private fun generateTypeTable() = HashMap<String, TypeBlock>().apply {
        packageBlock?.listAllSpecTypePair()?.forEach {
            it.listTypeBlocks().forEach { block ->
                var type = block.typeName
                if (!type.endsWith("s")) type = "${type}s"
                val properName = "values${block.qualifiers}/${type}"
                assert(!this.contains(properName)) { "multiple valid values or something idk" }
                // block.listEntries(true).forEach { logger.info("entry: ${it.name}, ${it.typeName}") }
                this[properName] = block
            }
        }
    }

    internal var typeTable = generateTypeTable()

    internal fun encodeValues(qualifiers: String, type: String, doc: XMLDocument) {
        valuesEncoder!!.encodeValuesXml(qualifiers, type, doc)
        typeTable = generateTypeTable()
    }


    /**
     * The metadata of the [Apk].
     */
    val packageMetadata = PackageMetadata()

    init {
        if (module.hasAndroidManifestBlock()) {
            val manifest = module.androidManifestBlock
            val appElement = manifest.applicationElement.startElement
            // Workaround for some apps (YouTube Music refuses to install without this).
            appElement.resXmlAttributeArray.remove(appElement.getAttribute(AndroidManifestBlock.ID_extractNativeLibs))
            manifest.refresh()
            if (manifest.versionName != null) {
                packageMetadata.packageName = module.androidManifestBlock.packageName
                packageMetadata.packageVersion = module.androidManifestBlock.versionName
            }
        }
    }


    /**
     * A map of resource files from their actual name to their archive name.
     * Example: res/drawable-hdpi/icon.png -> res/4a.png
     */
    internal val resFileTable =
        if (tableBlock != null) module.listResFiles().associate { "res/${it.buildPath()}" to it.filePath } else null

    data class ResourceElement(val type: String, val name: String, val id: Long)

    // TODO: move this to base only.
    val resourceMap: List<ResourceElement> = typeTable.flatMap { (type, typeBlock) ->
        typeBlock.listEntries(true).map { ResourceElement(it.typeName, it.name, it.resourceId.toLong()).apply { if (name == "app_theme_appearance_dark") logger.info("FOUND IT IN ${this@Apk}, ${this}") } }
    }

    // internal fun getEntry(type: String, name: String): Entry? = typeTable[type]?.entryArray?.listItems()?.find { it.name == name }

    /**
     * Open a [app.revanced.patcher.apk.File]
     */
    fun openFile(path: String) = File(
        path, logger, when {
            path == "res/values/public.xml" -> throw ApkException.Encode("Editing the resource table is not supported.")
            path.startsWith("res/values") -> {
                val s = path.removePrefix("res/").removeSuffix(".xml") // values-v29/drawables
                val parsingArray = s.removePrefix("values").split('/')
                val qualifiers = parsingArray.first()
                val type = parsingArray.last().let {
                    if (it != "plurals") it.removeSuffix("s") else it
                }
                ValuesCoder(
                    typeTable[s],
                    qualifiers,
                    type,
                    this
                )
            }

            else -> ArchiveCoder(path, this)
        }
    )

    /**
     * Get the resource directory of the apk file.
     *
     * @param options The patcher context to resolve the resource directory for the [Apk] file.
     * @return The resource directory of the [Apk] file.
     */
    internal fun getResourceDirectory(options: PatcherOptions) = options.resourceDirectory.resolve(toString())

    /**
     * @param out The [File] to write to.
     */
    open fun save(out: File) {
        module.writeApk(out)
        ZipAlign.align4(out)
    }

    /**
     * Decode resources for a [Apk].
     * Note: This function does not respect the patchers [ResourceDecodingMode] :trolley:.
     *
     * @param options The [PatcherOptions] to decode the resources with.
     * @param mode The [ResourceDecodingMode] to use.
     */
    internal open fun emitResources(options: PatcherOptions, mode: ResourceDecodingMode) {
        /*
        try {
            ApkModuleXmlDecoder(module).decodeTo(getResourceDirectory(options))
        } catch (e: Exception) {
            throw ApkException.Decode("Failed to decode resources", e)
        }
         */
    }

    /**
     * Refresh updated resources for an [Apk.file].
     *
     * @param options The [PatcherOptions] to write the resources with.
     */
    internal open fun refreshResources(options: PatcherOptions) {
        /*
        try {
            val encoder = ApkModuleXmlEncoder()
            encoder.scanDirectory(getResourceDirectory(options))
            module = encoder.apkModule
        } catch (e: Exception) {
            throw ApkException.Write("Failed to refresh resources: $e", e)
        }
         */
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
        class Language(filePath: String, logger: Logger) : Split(filePath, logger) {
            override fun toString() = "language"
        }

        /**
         * The split apk file which contains libraries.
         *
         * @param filePath The path to the apk file.
         */
        class Library(filePath: String, logger: Logger) : Split(filePath, logger) {
            override fun toString() = "library"
            override fun emitResources(options: PatcherOptions, mode: ResourceDecodingMode) {
                APKArchive.loadZippedApk(file).extract(getResourceDirectory(options))
            }

            override fun refreshResources(options: PatcherOptions) {}

            override fun save(out: File) {
                file.copyTo(out)
            }
        }

        /**
         * The split apk file which contains assets.
         *
         * @param filePath The path to the apk file.
         */
        class Asset(filePath: String, logger: Logger) : Split(filePath, logger) {
            override fun toString() = "asset"
        }
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
        override fun toString() = "base"
    }

    internal inner class BytecodeData {
        private val opcodes: Opcodes

        /**
         * The classes and proxied classes of the [Base] apk file.
         */
        val classes = ProxyBackedClassList(
            MultiDexIO.readDexFile(
                true, file, Patcher.dexFileNamer, null, null
            ).also { opcodes = it.opcodes }.classes
        )

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
            }.map {
                archive.add(ByteInputSource(it.value.readAt(0).use { stream -> stream.readAllBytes() }, it.key))
            }
        }
    }

    /**
     * The type of decoding the resources.
     */
    internal enum class ResourceDecodingMode {
        /**
         * Decode all resources.
         */
        FULL,

        /**
         * Decode the manifest file only.
         */
        MANIFEST_ONLY,
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
     * An exception thrown in [decodeResources] or [writeResources].
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