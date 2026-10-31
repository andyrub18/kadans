import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices) apply false
}

// google-services.json is per-owner and gitignored; the build works without it (push stays off).
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.googleServices.get().pluginId)
}

// Release signing with the upload key Play App Signing expects. The keystore and its passwords never enter the
// repository: they live in ~/.gradle/gradle.properties on the release machine (DEPLOYMENT → Building the apps).
// Without them a release build comes out unsigned, which Play Console refuses.
val uploadStoreFile: String? = providers.gradleProperty("kadans.upload.storeFile").orNull

// A release build speaks https only (res/xml/network_security_config.xml), so it must be told an https server: without
// -Pkadans.apiBaseUrl it would talk to the emulator's http://10.0.2.2:5199 and reach nothing. Checked before it builds.
val apiBaseUrl = providers.gradleProperty("kadans.apiBaseUrl")
val checkReleaseServer by tasks.registering {
    val url = apiBaseUrl
    doLast {
        val server = url.orNull?.trim().orEmpty()
        check(server.startsWith("https://") && server.length > "https://".length) {
            "A release build needs the server it is for, over https: -Pkadans.apiBaseUrl=https://api.<domain> " +
                "(got ${server.ifEmpty { "none" }}). DEPLOYMENT → Building the apps."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseServer) }

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.ktor.client.okhttp)
}

android {
    namespace = "app.kadans.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.kadans"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Every upload to Play needs a higher versionCode: -Pkadans.versionCode=2, 3, …
        versionCode = providers.gradleProperty("kadans.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("kadans.version").get() // one version for every app (gradle.properties)
    }
    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = file(uploadStoreFile)
                storePassword = providers.gradleProperty("kadans.upload.storePassword").get()
                keyAlias = providers.gradleProperty("kadans.upload.keyAlias").get()
                keyPassword = providers.gradleProperty("kadans.upload.keyPassword").get()
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
