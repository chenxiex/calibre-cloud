import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

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
            manifestPlaceholders["appAuthRedirectScheme"] = "io.github.chenxiex.calibrecloud.debug.disabled"
        }
        release {
            resValue("string", "app_name", "Calibre Cloud")
            manifestPlaceholders["appAuthRedirectScheme"] = "io.github.chenxiex.calibrecloud.disabled"
        }
    }

    buildFeatures {
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

// Resolve application and test classpaths; build/lint tasks also lock their tool configurations.
tasks.register("resolveLockedDependencies") {
    description = "Resolves application and test classpaths for dependency lock generation."
    doLast {
        configurations.filter {
            it.isCanBeResolved && (it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath"))
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
