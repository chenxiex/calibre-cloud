import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// All three properties form one configuration: incomplete forks retain local authorization.
val oneDriveProperties = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}
val oneDriveKeys = listOf("onedrive.clientId", "onedrive.redirectUri", "onedrive.debugRedirectUri")
val oneDriveValues = oneDriveKeys.map { oneDriveProperties.getProperty(it)?.trim().orEmpty() }
val oneDriveConfigured = oneDriveValues.all { it.isNotEmpty() }

// Emit Java literals without allowing a property to escape the generated string.
fun javaString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> if (character.code !in 32..126) {
                append("\\u%04x".format(character.code))
            } else append(character)
        }
    }
    append('"')
}

fun callbackUri(value: String, property: String): URI {
    fun invalid(reason: String): Nothing = throw GradleException("$property: $reason")
    val uri = try { URI(value) } catch (_: Exception) { invalid("invalid native callback URI") }
    val scheme = uri.scheme ?: invalid("a custom scheme is required")
    if (!scheme.matches(Regex("[a-z][a-z0-9+.-]*")) ||
        scheme in setOf("http", "https", "file", "content", "android.resource", "intent", "data", "javascript")
    ) invalid("use a lowercase custom native callback scheme")
    if (uri.isOpaque || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        uri.port != -1 || (uri.rawAuthority != null && uri.host == null)
    ) invalid("credentials, query, fragment, port and opaque callbacks are not supported")
    if (uri.host != null && !uri.host.matches(Regex("[a-z0-9]+([.-][a-z0-9]+)*"))) {
        invalid("use a lowercase literal host without wildcards")
    }
    if (uri.rawPath.isNullOrEmpty() || !uri.rawPath.startsWith("/") ||
        uri.rawPath.contains('%') || uri.rawPath.any { it.code !in 33..126 } ||
        uri.rawPath.any { it in listOf('<', '>', '&', '\\', '"', '\'') }
    ) invalid("use a nonempty absolute literal path without escapes or XML characters")
    return uri
}

val oneDriveCallbacks = if (oneDriveConfigured) {
    if (oneDriveValues[0].any { it.isISOControl() }) {
        throw GradleException("onedrive.clientId: control characters are not allowed")
    }
    val release = callbackUri(oneDriveValues[1], oneDriveKeys[1])
    val debug = callbackUri(oneDriveValues[2], oneDriveKeys[2])
    // Android ignores path constraints without a host; scheme-only filters cover all paths.
    if (release.scheme == debug.scheme &&
        (release.host == null || debug.host == null ||
            (release.host == debug.host && release.path == debug.path))
    ) throw GradleException("onedrive.redirectUri / onedrive.debugRedirectUri: callback receiver ranges overlap")
    mapOf("release" to release, "debug" to debug)
} else emptyMap()

android {
    namespace = "io.github.chenxiex.calibrecloud"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.chenxiex.calibrecloud"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testApplicationId = "io.github.chenxiex.calibrecloud.debug.test"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Calibre Cloud Debug")
        }
        release {
            resValue("string", "app_name", "Calibre Cloud")
        }
    }

    buildTypes.configureEach {
        val callback = oneDriveCallbacks[name]
        val disabledScheme = "io.github.chenxiex.calibrecloud" +
            (if (name == "debug") ".debug" else "") + ".disabled"
        // Empty host attributes are not equivalent to absent attributes on Android.
        // Choose a literal template; every value still comes from the shared parsed callback.
        sourceSets.getByName(name).manifest.srcFile(
            "oauth-manifests/${if (callback?.host != null) "hosted" else "hostless"}.xml",
        )
        manifestPlaceholders["appAuthRedirectScheme"] = callback?.scheme ?: disabledScheme
        manifestPlaceholders["appAuthRedirectHost"] = callback?.host.orEmpty()
        manifestPlaceholders["appAuthRedirectPath"] = if (callback?.host != null) callback.path else ""
        manifestPlaceholders["appAuthRedirectEnabled"] = (callback != null).toString()
        buildConfigField("boolean", "ONEDRIVE_CONFIGURED", (callback != null).toString())
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", javaString(if (callback != null) oneDriveValues[0] else ""))
        buildConfigField("String", "ONEDRIVE_REDIRECT_URI", javaString(callback?.toString().orEmpty()))
    }

    // Reuse the repository's independent Calibre sample only in the test APK.
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("assets"))

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

// Resolve application and test classpaths plus the device-test utilities and Unified Test Platform
// runner that connected tests resolve at run time; build/lint tasks also lock their tool configurations.
tasks.register("resolveLockedDependencies") {
    description = "Resolves application and test classpaths for dependency lock generation."
    doLast {
        configurations.filter {
            it.isCanBeResolved && (it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath") ||
                it.name == "androidTestUtil" || it.name.startsWith("_internal-unified-test-platform"))
        }.forEach { configuration ->
            // AGP selects artifact types in its tasks; locking only needs the complete graph.
            configuration.incoming.resolutionResult.allDependencies.forEach { dependency ->
                if (dependency is org.gradle.api.artifacts.result.UnresolvedDependencyResult) {
                    throw dependency.failure
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
    implementation(libs.appauth)
    implementation(libs.okhttp)
    implementation(libs.androidx.work)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.compose.ui.test)
}
