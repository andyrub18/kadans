import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        // Named so the window's X11 class matches the installed launcher (kadans/Kadans.kt).
        mainClass = "kadans.Kadans"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Kadans"
            packageVersion = "1.0.0"
            // The runtime is cut down to what the app uses. Beyond java.desktop's modules, the Linux tray's D-Bus
            // (dbus-java) needs the user id for its login (jdk.security.auth) and the Unix socket's options (jdk.net).
            modules("jdk.security.auth", "jdk.net")
            // Drawn by branding/generate.py. macOS needs an .icns, made on a Mac with the DMG (not yet).
            linux {
                iconFile.set(project.file("icons/kadans.png"))
                menuGroup = "Office" // the desktop menu's section for planners and calendars ("Unknown" without it)
            }
            windows { iconFile.set(project.file("icons/kadans.ico")) }
        }
    }
}
