@file:Suppress("MemberVisibilityCanBePrivate")

package app.revanced.patcher.apk

import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.logging.Logger
import app.revanced.patcher.patch.PatchResult
import app.revanced.patcher.util.ProxyBackedClassList
import com.reandroid.apk.*
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.archive.APKArchive
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.ZipAlign
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.common.Frameworks
import com.reandroid.common.TableEntryStore

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

    // Workaround for some apps (YouTube Music refuses to install without this).
    init {
        if (module.hasAndroidManifestBlock()) {
            val manifest = module.androidManifestBlock
            val appElement = manifest.applicationElement.startElement
            appElement.resXmlAttributeArray.remove(appElement.getAttribute(AndroidManifestBlock.ID_extractNativeLibs))
            manifest.refresh()
        }
    }
    /**
     * EntryStore for decoding.
     */
    internal val entryStore = TableEntryStore().apply {
        add(Frameworks.getAndroid())
        add(module.tableBlock)
    }

    /**
     * EncodeMaterials for encoding.
     */
    internal val encodeMaterials = EncodeMaterials.create(module.tableBlock)

    /**
     * Open a [app.revanced.patcher.apk.File]
     */
    fun openFile(path: String) = File(when {
        // path == "res/values/public.xml" -> ResourceTableCoder(...)
        path.startsWith("res/values") -> throw PatchResult.Error("res/values is an illusion!")
        else -> ArchiveCoder(path, this)
    })

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
     * The metadata of the [Apk].
     */
    val packageMetadata = PackageMetadata()

    // TODO: put these in the constructor of PackageMetadata instead.
    init {
        if (module.hasAndroidManifestBlock() && module.androidManifestBlock.versionName != null) {
            packageMetadata.packageName = module.androidManifestBlock.packageName
            packageMetadata.packageVersion = module.androidManifestBlock.versionName
        }
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

            override fun save(out: File) { file.copyTo(out) }
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
         * An exception when writing resources.
         *
         * @param message The exception message.
         * @param throwable The corresponding [Throwable].
         */
        open class Write(message: String, throwable: Throwable? = null) : ApkException(message, throwable) {
            /**
             * An exception when a resource directory could not be found while writing.
             **/
            object ResourceDirectoryNotFound : Write("Failed to find the resource directory")
        }
    }
}