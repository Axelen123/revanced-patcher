@file:Suppress("MemberVisibilityCanBePrivate")

package app.revanced.patcher.apk

import app.revanced.patcher.DomFileEditor
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.extensions.nullOutputStream
import app.revanced.patcher.util.ProxyBackedClassList
import app.revanced.patcher.util.dex.DexFile
import app.revanced.patcher.util.dom.DomUtil.doRecursively
/*
import brut.androlib.Androlib
import brut.androlib.AndrolibException
import brut.androlib.ApkDecoder
import brut.androlib.meta.MetaInfo
import brut.androlib.meta.UsesFramework
import brut.androlib.options.BuildOptions
import brut.androlib.res.AndrolibResources
import brut.androlib.res.data.ResPackage
import brut.androlib.res.data.ResTable
import brut.androlib.res.decoder.AXmlResourceParser
import brut.androlib.res.decoder.ResAttrDecoder
import brut.androlib.res.decoder.XmlPullStreamDecoder
import brut.androlib.res.xml.ResXmlPatcher
import brut.directory.ExtFile
import brut.directory.ZipUtils
 */
import com.reandroid.apk.ApkModule;
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder

import lanchon.multidexlib2.DexIO
import lanchon.multidexlib2.MultiDexIO
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.w3c.dom.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.io.path.copyTo

/**
 * The apk file that is to be patched.
 *
 * @param filePath The path to the apk file.
 */
sealed class Apk(filePath: String) {
    /**
     * The apk file.
     */
    open val file = File(filePath)

    /**
     * ARSCLib Apk module.
     */
    var module = ApkModule.loadApkFile(file)

    /**
     * The patched resources for the [Apk] given by the [app.revanced.patcher.Patcher].
     */
    var resources: File? = null
        internal set

    /**
     * The metadata of the [Apk].
     */
    val packageMetadata = PackageMetadata()

    /*
    internal fun getFile(path: String, options: PatcherOptions) =
        getResourceDirectory(options).resolve(path).also { out ->
            out.createNewFile()
            if (out.exists()) out else {
                module.listResFiles().firstNotNullOfOrNull {
                    if (it.filePath != path) null else
                        if (it.isBinaryXml) module.decodeXMLFile(it.inputSource.name)
                            .save(out, true) else it.inputSource.write(FileOutputStream(out))
                }
            }
        }
    */


    /**
     * The split apk file that is to be patched.
     *
     * @param filePath The path to the apk file.
     * @see Apk
     */
    sealed class Split(filePath: String) : Apk(filePath) {

        /**
         * The split apk file which contains language files.
         *
         * @param filePath The path to the apk file.
         */
        class Language(filePath: String) : Split(filePath) {
            override fun toString() = "language"
        }

        /**
         * The split apk file which contains libraries.
         *
         * @param filePath The path to the apk file.
         */
        class Library(filePath: String) : Split(filePath) {
            override fun toString() = "library"
        }

        /**
         * The split apk file which contains assets.
         *
         * @param filePath The path to the apk file.
         */
        class Asset(filePath: String) : Split(filePath) {
            override fun toString() = "asset"
        }
    }

    /**
     * The base apk file that is to be patched.
     *
     * @param filePath The path to the apk file.
     * @see Apk
     */
    class Base(filePath: String) : Apk(filePath) {
        /**
         * Data of the [Base] apk file.
         */
        internal val bytecodeData = BytecodeData()

        /**
         * The patched dex files for the [Base] apk file.
         */
        lateinit var dexFiles: List<DexFile>
            internal set

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
         * Write [classes] to [DexFile]s.
         *
         * @return The [DexFile]s.
         */
        internal fun writeDexFiles(): List<DexFile> {
            // Make sure to replace all classes with their proxy.
            val classes = classes.also(ProxyBackedClassList::applyProxies)
            val opcodes = opcodes

            // Create patched dex files.
            return mutableMapOf<String, MemoryDataStore>().also {
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
                DexFile(it.key, it.value.readAt(0))
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