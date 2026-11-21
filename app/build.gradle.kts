import java.io.DataInputStream
import java.io.File as JavaFile
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun detectKeystoreType(file: JavaFile): String {
    val stream = DataInputStream(file.inputStream())
    val magic = try { stream.readInt() } catch (_: Exception) { 0 }
    stream.close()
    return if (magic == 0xFEEDFEED.toInt()) "JKS" else "PKCS12"
}

android {
    namespace = "salu.Kandoraq.Miauterm"
    compileSdk = 34

    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.miauterm.terminal"
        minSdk = 24

        targetSdk = 28
        versionCode = 30010
        versionName = "0.1.4-coral"

        val rootfsDigest = MessageDigest.getInstance("SHA-256")
            .digest(file("src/main/assets/debian-arm64.tar.zst").readBytes())
            .joinToString("") { "%02x".format(it) }
        buildConfigField("String", "ROOTFS_SHA256", "\"$rootfsDigest\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    sourceSets.getByName("main").assets.srcDir(file("$buildDir/generated/port-remap-assets"))

    androidResources {
        noCompress += listOf("zst")
    }

    val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
    val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    val resolvedCustomKeystore = if (!releaseStoreFile.isNullOrEmpty()) {
        val candidate = file(releaseStoreFile)
        if (candidate.exists()) candidate else rootProject.file(releaseStoreFile)
    } else {
        null
    }

    val (activeKeystore, activeStorePass, activeAlias, activeKeyPass) = when {
        resolvedCustomKeystore != null &&
            resolvedCustomKeystore.exists() &&
            !releaseStorePassword.isNullOrEmpty() &&
            !releaseKeyAlias.isNullOrEmpty() &&
            !releaseKeyPassword.isNullOrEmpty() -> {
            listOf(resolvedCustomKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
        }
        else -> {
            listOf(null, null, null, null)
        }
    }
    val hasReleaseSigning = activeKeystore != null

    if (!hasReleaseSigning) {
        project.tasks.matching {
            it.name in setOf("assembleRelease", "bundleRelease", "packageRelease")
        }.configureEach {
            doFirst {
                throw GradleException(
                    "Release build requires the release signing environment variables (RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD)."
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning && activeKeystore != null) {
                storeType = detectKeystoreType(activeKeystore as java.io.File)
                storeFile = activeKeystore as java.io.File
                storePassword = activeStorePass as String
                keyAlias = activeAlias as String
                keyPassword = activeKeyPass as String
            } else {
                storeFile = rootProject.file("release-keystore-not-configured.p12")
                storePassword = "not-configured"
                keyAlias = "not-configured"
                keyPassword = "not-configured"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true

            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"

                // Robolectric downloads Android SDK jars outside Gradle's repository resolver.
                // Use Maven Central's canonical endpoint so CI can fetch the matching checksums.
                it.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://repo.maven.apache.org/maven2"
                )

                it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
        warning.add("ExpiredTargetSdkVersion")
    }
}

dependencies {
    implementation(project(":core:terminal-engine"))
    implementation("androidx.annotation:annotation:1.7.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.drawerlayout)
    implementation(libs.androidx.recyclerview)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
