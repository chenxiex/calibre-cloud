package io.github.chenxiex.calibrecloud.about

import io.github.chenxiex.calibrecloud.ui.LICENSE_TEXTS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the About page's declarations in step with the release build: the unit test runs in the
 * module directory, where the dependency lock and the resources are plain files.
 */
class ThirdPartyNoticesTest {
    private val notices = ThirdPartyNotices.parse(File("src/main/res/raw/third_party_notices.txt").readText())

    /** group:artifact of every module the release APK packages, from the strict dependency lock. */
    private val releaseModules = File("gradle.lockfile").readLines()
        .filter { line -> line.substringAfter('=', "").split(',').contains("releaseRuntimeClasspath") }
        .map { it.substringBefore('=').split(':').take(2).joinToString(":") }
        .toSet()

    @Test
    fun everyReleaseRuntimeModuleIsDeclaredExactlyOnceAndNothingElse() {
        assertTrue(releaseModules.isNotEmpty())
        val declared = notices.flatMap { it.modules }
        assertEquals("Modules declared twice", declared.size, declared.toSet().size)
        assertEquals("Release modules without a notice", emptySet<String>(), releaseModules - declared.toSet())
        assertEquals("Notices for modules the release no longer packages", emptySet<String>(), declared.toSet() - releaseModules)
    }

    @Test
    fun everyLicenseIsGplCompatibleAndHasItsFullText() {
        val licenses = notices.map { it.license }.toSet()
        assertEquals(emptySet<String>(), licenses - ThirdPartyNotices.GPL_COMPATIBLE_LICENSES)
        assertEquals(emptySet<String>(), licenses - LICENSE_TEXTS.keys)
        notices.forEach { assertTrue(it.name, it.copyright.startsWith("Copyright ")) }
    }

    @Test
    fun bundledProjectLicenseIsTheRepositoryLicense() {
        assertArrayEquals(File("../LICENSE").readBytes(), File("src/main/res/raw/license_gpl_3_0.txt").readBytes())
    }

    @Test
    fun malformedDeclarationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ThirdPartyNotices.parse("[A]\nlicense = MIT\nmodule = a:b") }
        assertThrows(IllegalArgumentException::class.java) { ThirdPartyNotices.parse("module = a:b") }
        assertThrows(IllegalArgumentException::class.java) { ThirdPartyNotices.parse("[A]\ncopyright = Copyright A\nlicense = MIT\nversion = 1") }
        val parsed = ThirdPartyNotices.parse("# c\n[A]\ncopyright = Copyright A\nlicense = MIT\nsource = x\n\n[B]\ncopyright = Copyright B\nlicense = MIT\nmodule = g:a\n")
        assertEquals(listOf(NoticeComponent("A", "Copyright A", "MIT", emptyList(), "x"),
            NoticeComponent("B", "Copyright B", "MIT", listOf("g:a"), null)), parsed)
    }
}
