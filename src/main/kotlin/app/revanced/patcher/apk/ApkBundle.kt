package app.revanced.patcher.apk

import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.apk.Apk.ResourceDecodingMode
import app.revanced.patcher.logging.Logger
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import com.reandroid.archive.ZipAlign
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * An [Apk] file of type [Apk.Split].
 *
 * @param base The apk file of type [Apk.Base].
 * @param split The [Apk.Split] files.
 */
class ApkBundle(
    val base: Apk.Base,
    split: Split? = null,
    val logger: Logger
) {
    /**
     * The [Apk.Split] files.
     */
    var split = split
        internal set

    /**
     * TODO: make this return [Apk]
     * Merge all [Apk.Split] files to [Apk.Base].
     * This will set [split] to null.
     * @param options The [PatcherOptions] to write the resources with.
     */
    private fun merge() = ApkBundle().apply {
        setAPKLogger(logger)
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
     * Refresh updated resources for the files in [ApkBundle].
     *
     * @param options The [PatcherOptions] to write the resources with.
     * @return A sequence of the [Apk] files which resources are being written.
     */
    internal fun refreshResources(options: PatcherOptions) = sequence {
        with(base) {
            refreshResources(options)

            yield(SplitApkResult.Write(this))
        }

        split?.all?.forEach { splitApk ->
            with(splitApk) {
                var exception: Apk.ApkException.Encode? = null

                try {
                    refreshResources(options)
                } catch (writeException: Apk.ApkException.Encode) {
                    exception = writeException
                }

                yield(SplitApkResult.Write(this, exception))
            }
        }
    }

    /**
     * Decode resources for the files in [ApkBundle].
     *
     * @param options The [PatcherOptions] to decode the resources with.
     * @param mode The [Apk.ResourceDecodingMode] to use.
     * @return A sequence of the [Apk] files which resources are being decoded.
     */
    internal fun emitResources(options: PatcherOptions, mode: ResourceDecodingMode) = sequence {
        with(base) {
            yield(this)
            emitResources(options, mode)
        }

        split?.all?.forEach {
            yield(it)
            it.emitResources(options, mode)
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
        class Write(apk: Apk, exception: Apk.ApkException.Encode? = null) : SplitApkResult(apk, exception)
    }
}