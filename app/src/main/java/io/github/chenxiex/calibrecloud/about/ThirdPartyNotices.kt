package io.github.chenxiex.calibrecloud.about

/**
 * One third-party component of the release build: its copyright line, the SPDX identifier of its
 * license and the Maven modules (group:artifact) it covers. [source] names bundled files that are not
 * a module, such as icon resources.
 */
data class NoticeComponent(
    val name: String,
    val copyright: String,
    val license: String,
    val modules: List<String>,
    val source: String?,
)

/**
 * Reads `res/raw/third_party_notices.txt`: `[name]` starts a component, followed by `copyright =`,
 * `license =`, any number of `module =` and an optional `source =` line; `#` lines are comments. The
 * texts are shown as written: copyright and license names are not translated.
 */
object ThirdPartyNotices {
    /** SPDX identifiers whose terms allow distribution in this GPL-3.0-or-later application. */
    val GPL_COMPATIBLE_LICENSES = setOf("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause")

    fun parse(text: String): List<NoticeComponent> {
        val components = mutableListOf<NoticeComponent>()
        var name: String? = null
        val fields = mutableMapOf<String, String>()
        val modules = mutableListOf<String>()
        fun finish() {
            val current = name ?: return
            components += NoticeComponent(
                current,
                requireNotNull(fields["copyright"]) { "$current has no copyright" },
                requireNotNull(fields["license"]) { "$current has no license" },
                modules.toList(),
                fields["source"],
            )
            fields.clear()
            modules.clear()
        }
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            if (line.startsWith("[") && line.endsWith("]")) {
                finish()
                name = line.substring(1, line.length - 1)
                return@forEach
            }
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim()
            require(name != null && value.isNotEmpty()) { "Malformed notice line: $line" }
            when (key) {
                "module" -> modules += value
                "copyright", "license", "source" -> require(fields.put(key, value) == null) { "Repeated $key in $name" }
                else -> throw IllegalArgumentException("Unknown notice field: $key")
            }
        }
        finish()
        return components
    }
}
