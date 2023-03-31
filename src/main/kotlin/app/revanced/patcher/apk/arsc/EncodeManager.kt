package app.revanced.patcher.apk.arsc

import com.reandroid.apk.AndroidFrameworks
import com.reandroid.apk.xmlencoder.EncodeUtil
import com.reandroid.arsc.util.FrameworkTable
import com.reandroid.xml.XMLDocument
import java.io.File

val frameworkTable: FrameworkTable = AndroidFrameworks.getLatest().tableBlock

@Deprecated("TODO: do this inside the backends instead.")
internal fun getTypeIndex(s: String): Pair<String, String>? {
    if (!s.startsWith("res")) {
        return null
    }
    val file = File(s)
    return if (s.startsWith("res/values")) EncodeUtil.getQualifiersFromValuesXml(file) to EncodeUtil.getTypeNameFromValuesXml(
        file
    ) else EncodeUtil.getQualifiersFromResFile(file) to EncodeUtil.getTypeNameFromResFile(file)
}