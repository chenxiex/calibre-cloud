package io.github.chenxiex.calibrecloud.files

import io.github.chenxiex.calibrecloud.model.BookFormat
import java.util.Locale

/**
 * Names and types offered to readers. The disk name stays the opaque generation; only the name a reader
 * sees is derived from the title, so books with the same title never share a file.
 */
object BookFileNames {
    /** Longest name in UTF-8 bytes, the usual file name limit of reader storage. */
    private const val MAX_BYTES = 255

    /**
     * "Title-ID.ext", used both as the single URI path segment and as the provider's display name:
     * some readers name their imported file and shelf entry after the URI path, others after the display
     * name, so both carry the book ID that keeps same-title books of one library apart, with the title
     * first. Control characters become spaces, invisible format characters (such as bidi overrides that
     * could disguise the extension) are dropped, path separators become "_", and surrounding spaces and
     * dots are trimmed; other title text is kept. A title that is empty after this gives "[untitled].ext",
     * where [untitled] is the resource-formatted "未命名书籍-<ID>". The ID suffix is never cut when the
     * title is shortened.
     */
    fun readerName(title: String, sourceId: Long, format: BookFormat, untitled: String): String {
        val extension = format.value.lowercase(Locale.ROOT)
        val base = normalize(title)
        if (base.isEmpty()) return "${truncate(normalize(untitled), MAX_BYTES - 1 - extension.length)}.$extension"
        val suffix = "-$sourceId.$extension"
        return truncate(base, MAX_BYTES - suffix.length) + suffix
    }

    private fun normalize(value: String): String {
        val text = StringBuilder()
        var offset = 0
        while (offset < value.length) {
            val point = value.codePointAt(offset)
            offset += Character.charCount(point)
            when {
                Character.isISOControl(point) || Character.isWhitespace(point) || Character.isSpaceChar(point) -> text.append(' ')
                Character.getType(point) == Character.FORMAT.toInt() -> Unit
                point == '/'.code || point == '\\'.code -> text.append('_')
                else -> text.appendCodePoint(point)
            }
        }
        return text.toString().replace(Regex(" {2,}"), " ").trim(' ', '.')
    }

    /** Cuts whole code points so the UTF-8 form fits [limit] bytes. */
    private fun truncate(value: String, limit: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= limit) return value
        val text = StringBuilder()
        var bytes = 0
        var offset = 0
        while (offset < value.length) {
            val point = value.codePointAt(offset)
            val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > limit) break
            text.appendCodePoint(point)
            bytes += size
            offset += Character.charCount(point)
        }
        return text.toString().trimEnd(' ', '.')
    }
}

/**
 * MIME types of common Calibre formats. Any other format uses the system's extension mapping
 * ([system]), and failing that a generic binary type, so the system offers whatever reader claims it;
 * no format is converted.
 */
object BookMimeTypes {
    private val known = mapOf(
        "EPUB" to "application/epub+zip",
        "KEPUB" to "application/epub+zip",
        "PDF" to "application/pdf",
        "MOBI" to "application/x-mobipocket-ebook",
        "PRC" to "application/x-mobipocket-ebook",
        "AZW" to "application/vnd.amazon.ebook",
        "AZW3" to "application/vnd.amazon.mobi8-ebook",
        "FB2" to "application/x-fictionbook+xml",
        "DJVU" to "image/vnd.djvu",
        "CBZ" to "application/vnd.comicbook+zip",
        "CBR" to "application/vnd.comicbook-rar",
        "CB7" to "application/x-cb7",
        "TXT" to "text/plain",
        "TXTZ" to "application/zip",
        "RTF" to "application/rtf",
        "HTML" to "text/html",
        "HTM" to "text/html",
        "HTMLZ" to "application/zip",
        "ZIP" to "application/zip",
        "DOC" to "application/msword",
        "DOCX" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "ODT" to "application/vnd.oasis.opendocument.text",
        "LIT" to "application/x-ms-reader",
        "LRF" to "application/x-sony-bbeb",
        "PDB" to "application/vnd.palm",
        "SNB" to "application/x-snb-ebook",
        "TCR" to "application/x-tcr-ebook",
    )

    const val FALLBACK = "application/octet-stream"

    fun of(format: BookFormat, system: (String) -> String?): String =
        known[format.value] ?: system(format.value.lowercase(Locale.ROOT)) ?: FALLBACK
}
