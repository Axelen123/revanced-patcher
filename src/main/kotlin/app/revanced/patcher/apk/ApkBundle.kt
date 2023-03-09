package app.revanced.patcher.apk

import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.apk.Apk.ResourceDecodingMode
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import com.reandroid.archive.ZipAlign
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File

/**
 * An [Apk] file of type [Apk.Split].
 *
 * @param base The apk file of type [Apk.Base].
 * @param split The [Apk.Split] files.
 */
class ApkBundle(
    val base: Apk.Base,
    split: Split? = null
) {
    /**
     * The [Apk.Split] files.
     */
    var split = split
        internal set

    internal var module = if (split == null) base.module else merge()


    /**
     * Get the resource directory of the [ApkBundle].
     *
     * @param options The patcher context to resolve the resource directory for the [ApkBundle].
     * @return The resource directory of the [ApkBundle].
     */
    protected fun getResourceDirectory(options: PatcherOptions) = options.resourceDirectory.resolve(toString())

    /**
     * Get a file from the resources of the [ApkBundle].
     *
     * @param path The path of the resource file.
     * @param options The patcher context to resolve the resource directory for the [ApkBundle].
     * @return A [File] instance for the resource file.
     */
    internal fun getFile(path: String, options: PatcherOptions): File? {
        val f = getResourceDirectory(options).resolve(path)
        return if (!f.exists()) null else f
    }

    /**
     * Merge all [Apk.Split] files to [Apk.Base].
     * This will set [split] to null.
     * @param options The [PatcherOptions] to write the resources with.
     */
    private fun merge() = ApkBundle().apply {
        addModule(base.module)
        split?.all?.forEach { addModule(it.module) }
    }.mergeModules().also {
        // Sanitize the manifest.
        if (it.hasAndroidManifestBlock()) {
            val manifest = it.androidManifestBlock
            val appElement = manifest.applicationElement.startElement
            arrayOf(
                AndroidManifestBlock.ID_isSplitRequired,
                AndroidManifestBlock.ID_extractNativeLibs
            ).forEach { id -> appElement.resXmlAttributeArray.remove(appElement.getAttribute(id)) }
            // TODO: maybe delet signature and vending stuff idk
            manifest.refresh()
        }
    }

    /**
     * Save and zipalign the bundle.
     * @param out The [File] to write to.
     */
    fun save(out: File) {
        module.writeApk(out)
        ZipAlign.align4(out)
    }

    /**
     * Decode resources for in an [ApkBundle].
     * Note: This function does not respect the patchers [ResourceDecodingMode] :trolley:.
     *
     * @param options The [PatcherOptions] to decode the resources with.
     * @param mode The [ResourceDecodingMode] to use.
     */
    internal fun emitResources(options: PatcherOptions, mode: ResourceDecodingMode) {
        try {
            ApkModuleXmlDecoder(module).decodeTo(getResourceDirectory(options))
        } catch (e: Exception) {
            throw Apk.ApkException.Decode("Failed to decode resources", e)
        }
    }

    /**
     * Refresh updated resources for a [ApkBundle].
     *
     * @param options The [PatcherOptions] to write the resources with.
     */
    internal fun refreshResources(options: PatcherOptions) {
        try {
            val encoder = ApkModuleXmlEncoder()
            encoder.scanDirectory(getResourceDirectory(options))
            module = encoder.apkModule
        } catch (e: Exception) {
            throw Apk.ApkException.Write("guhh", e)
        }
    }

    /**
     * Class for [Apk.Split].
     *
     * @param library The apk file of type [Apk.Base].
     * @param asset The apk file of type [Apk.Base].
     * @param language The apk file of type [Apk.Base].
     */
    class Split(
        library: Apk.Split.Library,
        asset: Apk.Split.Asset,
        language: Apk.Split.Language
    ) {
        var library = library
            internal set
        var asset = asset
            internal set
        var language = language
            internal set

        val all get() = listOfNotNull(library, asset, language)
    }

    /**
     * The result of writing a [Split] [Apk] file.
     *
     * @param apk The corresponding [Apk] file.
     * @param exception The optional [Apk.ApkException] when an exception occurred.
     */
    sealed class SplitApkResult(val apk: Apk, val exception: Apk.ApkException? = null) {
        /**
         * The result of writing a [Split] [Apk] file.
         *
         * @param apk The corresponding [Apk] file.
         * @param exception The optional [Apk.ApkException] when an exception occurred.
         */
        class Write(apk: Apk, exception: Apk.ApkException.Write? = null) : SplitApkResult(apk, exception)
    }
}