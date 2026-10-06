package io.github.chenxiex.calibrecloud.storage.onedrive

import org.json.JSONArray
import org.json.JSONObject

/** Injectable only to permit network fixtures on a JVM without Android's JSON implementation. */
fun interface GraphJsonDecoder {
    fun decode(value: String): Map<String, Any?>
}

object AndroidGraphJsonDecoder : GraphJsonDecoder {
    override fun decode(value: String): Map<String, Any?> = objectValues(JSONObject(value))

    private fun objectValues(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith {
        convert(value.get(it))
    }

    private fun convert(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> objectValues(value)
        is JSONArray -> (0 until value.length()).map { convert(value.get(it)) }
        else -> value
    }
}
