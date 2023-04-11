package app.revanced.patcher.arsc

import app.revanced.patcher.apk.Apk
import com.reandroid.arsc.decoder.ValueDecoder
import com.reandroid.arsc.decoder.ValueDecoder.EncodeResult
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ValueType
import com.reandroid.arsc.value.array.ArrayBag
import com.reandroid.arsc.value.array.ArrayBagItem
import com.reandroid.arsc.value.style.StyleBag
import com.reandroid.arsc.value.style.StyleBagItem

sealed interface Resource {
    val complex: Boolean

    fun write(entry: Entry, apk: Apk)
}

sealed class ScalarResource(internal val valueType: ValueType) : Resource {
    override val complex = false
    internal abstract fun data(resources: Apk.Resources): Int

    override fun write(entry: Entry, apk: Apk) {
        entry.setValueAsRaw(valueType, data(apk.resources))
    }

    internal class Simple(valueType: ValueType, val value: Int) : ScalarResource(valueType) {
        override fun data(resources: Apk.Resources) = value
    }
}

internal fun encoded(encodeResult: EncodeResult?) = encodeResult?.let { ScalarResource.Simple(it.valueType, it.value) }
    ?: throw Apk.ApkException.Encode("Failed to encode value")

fun color(hex: String): ScalarResource = encoded(ValueDecoder.encodeColor(hex))
fun dimension(value: String): ScalarResource = encoded(ValueDecoder.encodeDimensionOrFraction(value))
fun float(n: Float): ScalarResource = ScalarResource.Simple(ValueType.FLOAT, n.toBits())
fun integer(n: Int): ScalarResource = ScalarResource.Simple(ValueType.INT_DEC, n)

class Array(private val elements: Collection<ScalarResource>) : Resource {
    override val complex = true

    override fun write(entry: Entry, apk: Apk) {
        ArrayBag.create(entry).addAll(elements.map { ArrayBagItem.create(it.valueType, it.data(apk.resources)) })
    }
}

class Style(private val elements: Map<String, ScalarResource>) : Resource {
    override val complex = true

    override fun write(entry: Entry, apk: Apk) {
        val style = StyleBag.create(entry)

        elements.forEach { (key, value) ->
            style[StyleBag.resolve(apk.resources.encodeMaterials, key)] = StyleBagItem.create(value.valueType, value.data(apk.resources))
        }
    }
}

class StringResource(val value: String) : ScalarResource(ValueType.STRING) {
    override fun data(resources: Apk.Resources) = resources.tableBlock.stringPool.getOrCreate(value).index
}