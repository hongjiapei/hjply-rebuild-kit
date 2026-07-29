import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.spotless)
}

fun loadProperties(bytes: ByteArray): Properties {
    val text = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
    return Properties().apply { load(StringReader(text)) }
}

fun getProps(propName: String): String {
    val propsInEnv = System.getenv("LOCAL_PROPERTIES")
    if (propsInEnv != null) {
        val props = loadProperties(Base64.getDecoder().decode(propsInEnv))
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        val props = loadProperties(propsFile.readBytes())
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    return ""
}

fun getSubscriptionUrl(): String {
    val propsFile = rootProject.file("subscription.properties")
    if (!propsFile.exists()) return ""
    val props = loadProperties(propsFile.readBytes())
    return props.getProperty("SUBSCRIPTION_URL")
        ?.trim()
        .orEmpty()
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun getVersionProps(propName: String): String {
    val propsFile = rootProject.file("version.properties")
    if (propsFile.exists()) {
        val props = loadProperties(propsFile.readBytes())
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    return ""
}

android {
    namespace = "io.nekohasekai.sfa"
    compileSdk = 36

    ndkVersion = "28.0.13004108"

    System.getenv("ANDROID_NDK_HOME")?.let { ndkPath = it }

    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "${projectDir}/schemas")
    }

    defaultConfig {
        applicationId = "com.hjply.rebuilt"
        minSdk = 21
        targetSdk = 36
        versionCode = getVersionProps("VERSION_CODE").toInt()
        versionName = getVersionProps("VERSION_NAME")
        base.archivesName.set("hjply-${versionName}")
        buildConfigField(
            "String",
            "HJPLY_SUBSCRIPTION_URL",
            buildConfigString(getSubscriptionUrl()),
        )
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = getProps("KEYSTORE_PASS")
            keyAlias = getProps("ALIAS_NAME")
            keyPassword = getProps("ALIAS_PASS")
        }
    }

    buildTypes {
        debug {
            if (getProps("KEYSTORE_PASS").isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            vcsInfo.include = false
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    flavorDimensions += "vendor"
    productFlavors {
        create("other") {
            minSdk = 23
        }
    }

    sourceSets {
        getByName("other") {
            java.directories.add("src/minApi23/java")
            aidl.directories.add("src/minApi23/aidl")
        }
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            include("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        fatal += "NewApi"
    }

}

dependencies {
    // libbox
    "otherImplementation"(files("libs/libbox.aar"))

    // API 23+ dependencies
    val lifecycleVersion23 = "2.10.0"
    val roomVersion23 = "2.8.4"
    val workVersion23 = "2.11.1"

    // Common dependencies (no API level difference)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    "otherImplementation"("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion23")
    "otherImplementation"("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion23")
    "otherImplementation"("androidx.lifecycle:lifecycle-process:$lifecycleVersion23")
    "otherImplementation"("androidx.room:room-runtime:$roomVersion23")
    "otherImplementation"("androidx.work:work-runtime-ktx:$workVersion23")
    "kspOther"("androidx.room:room-compiler:$roomVersion23")

    // Compose dependencies
    val composeBom23 = platform("androidx.compose:compose-bom:2026.02.00")
    val activityVersion23 = "1.12.4"
    val lifecycleComposeVersion23 = "2.10.0"

    "otherImplementation"(composeBom23)
    "otherImplementation"("androidx.compose.material3:material3")
    "otherImplementation"("androidx.compose.ui:ui")
    "otherImplementation"("androidx.activity:activity-compose:$activityVersion23")
    "otherImplementation"("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleComposeVersion23")

    testImplementation("junit:junit:4.13.2")

}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(mapOf(
                "ktlint_standard_backing-property-naming" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_max-line-length" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
            ))
    }
    java {
        target("src/**/*.java")
        googleJavaFormat()
    }
}
