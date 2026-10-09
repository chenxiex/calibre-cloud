package io.github.chenxiex.calibrecloud.ui

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.chenxiex.calibrecloud.BuildConfig
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.about.NoticeComponent
import io.github.chenxiex.calibrecloud.about.ThirdPartyNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full texts of the licenses third-party components use, by SPDX identifier; shown untranslated. */
internal val LICENSE_TEXTS: Map<String, Int> = mapOf("Apache-2.0" to R.raw.license_apache_2_0)

/** One paragraph of a paged text; a [heading] is bold and kept with the paragraph after it. */
internal data class TextParagraph(val text: String, val heading: Boolean = false)

/**
 * About (R30): version, the project license statement and third-party notices as static pages. The
 * full GPL text and the notices open from the buttons below the pages.
 */
@Composable
internal fun AboutPage(show: (MorePage) -> Unit) {
    val paragraphs = listOf(
        TextParagraph(stringResource(R.string.app_name), heading = true),
        TextParagraph(stringResource(R.string.about_version, BuildConfig.VERSION_NAME)),
        TextParagraph(stringResource(R.string.about_project_license), heading = true),
        TextParagraph(stringResource(R.string.about_license_statement)),
        TextParagraph(stringResource(R.string.about_commercial)),
        TextParagraph(stringResource(R.string.about_notices), heading = true),
        TextParagraph(stringResource(R.string.about_notices_summary)),
    )
    Column(Modifier.fillMaxSize().testTag("about_page")) {
        PagedText(paragraphs, "about", Modifier.weight(1f))
        HorizontalRule()
        ButtonRow {
            ActionButton(stringResource(R.string.about_open_license), true, Modifier.testTag("about_project_license")) { show(MorePage.PROJECT_LICENSE) }
            ActionButton(stringResource(R.string.about_open_notices), true, Modifier.testTag("about_notices")) { show(MorePage.NOTICES) }
        }
    }
}

/**
 * Third-party components of the release build from `res/raw/third_party_notices.txt`: name, copyright,
 * license and the modules covered. [openLicense] shows a license's full text.
 */
@Composable
internal fun NoticesPage(openLicense: (String) -> Unit) {
    val text = rawText(R.raw.third_party_notices)
    val notices = remember(text) { text?.let(ThirdPartyNotices::parse) }
    val paragraphs = notices?.flatMap { component -> noticeParagraphs(component) }
    Column(Modifier.fillMaxSize().testTag("notices_page")) {
        PagedText(paragraphs, "notices", Modifier.weight(1f))
        HorizontalRule()
        ButtonRow {
            notices.orEmpty().map { it.license }.distinct().filter { it in LICENSE_TEXTS }.forEach { license ->
                ActionButton(stringResource(R.string.about_license_full, license), true, Modifier.testTag("notice_license_$license")) {
                    openLicense(license)
                }
            }
        }
    }
}

@Composable
private fun noticeParagraphs(component: NoticeComponent): List<TextParagraph> = buildList {
    add(TextParagraph(component.name, heading = true))
    add(TextParagraph(component.copyright))
    add(TextParagraph(stringResource(R.string.about_notice_license, component.license)))
    component.source?.let { add(TextParagraph(stringResource(R.string.about_notice_source, it))) }
    if (component.modules.isNotEmpty()) add(TextParagraph(component.modules.joinToString("\n")))
}

/** A license's original text, reflowed into paragraphs and paged; never translated. */
@Composable
internal fun LicenseTextPage(@RawRes text: Int, tagPrefix: String) {
    val raw = rawText(text)
    val paragraphs = remember(raw) {
        raw?.split(Regex("\\n\\s*\\n"))?.map { block -> block.lines().joinToString(" ") { it.trim() }.trim() }
            ?.filter { it.isNotEmpty() }?.map { TextParagraph(it) }
    }
    PagedText(paragraphs, tagPrefix, Modifier.fillMaxSize().testTag("${tagPrefix}_page"))
}

/** Buttons below the paged text, at the page margins. */
@Composable
private fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_MARGIN), horizontalArrangement = Arrangement.spacedBy(SECTION_GAP), content = content)
}

/** A raw resource read off the main thread; null until read. */
@Composable
private fun rawText(@RawRes id: Int): String? {
    val resources = LocalResources.current
    val text by produceState<String?>(null, id) {
        value = withContext(Dispatchers.IO) { resources.openRawResource(id).bufferedReader().use { it.readText() } }
    }
    return text
}

/** A measured line or the gap after a paragraph. */
private class TextLine(val text: String, val height: Float, val bold: Boolean, val gap: Boolean)

/**
 * Static text in explicit pages: each paragraph is measured at the page width and split into its lines,
 * which are packed by height, so a page ends between lines and nothing scrolls. Paragraph gaps never
 * start or end a page and a heading stays with what follows. Null [paragraphs] show nothing yet.
 */
@Composable
internal fun PagedText(paragraphs: List<TextParagraph>?, tagPrefix: String, modifier: Modifier) {
    var page by rememberSaveable(tagPrefix) { mutableIntStateOf(0) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium.copy(color = INK)
    val bold = style.copy(fontWeight = FontWeight.Bold)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val inset = PAGE_MARGIN
        val width = with(density) { (maxWidth - inset * 2).roundToPx() }.coerceAtLeast(1)
        val lines = remember(paragraphs, width, style) {
            paragraphs.orEmpty().flatMap { paragraph ->
                val layout = measurer.measure(paragraph.text, if (paragraph.heading) bold else style, constraints = Constraints(maxWidth = width))
                (0 until layout.lineCount).map { line ->
                    TextLine(
                        paragraph.text.substring(layout.getLineStart(line), layout.getLineEnd(line, visibleEnd = true)),
                        with(density) { (layout.getLineBottom(line) - layout.getLineTop(line)).toDp().value },
                        paragraph.heading, gap = false,
                    )
                } + TextLine("", SECTION_GAP.value, bold = false, gap = true)
            }
        }
        val blocks = lines.map { PageBlock(it.height, keepWithNext = it.bold, separator = it.gap) }
        val pages = paginate(blocks, maxHeight.value).takeIf { it.size <= 1 } ?: paginate(blocks, (maxHeight - PAGE_BAR_HEIGHT).value)
        val current = page.coerceIn(0, pages.size - 1)
        PagedArea(current, pages.size, tagPrefix, { page = it }, Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = inset).testTag("${tagPrefix}_text")) {
                pages[current].forEach { index ->
                    val line = lines[index]
                    if (line.gap) Spacer(Modifier.height(line.height.dp))
                    else Text(line.text, Modifier.height(line.height.dp), style = if (line.bold) bold else style, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
