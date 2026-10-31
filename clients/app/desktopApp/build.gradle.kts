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
        // Kadans calls libX11 and libc at startup (LinuxInputMethod), as skiko loads its own library: allowed, not warned.
        jvmArgs("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            // The release workflow builds the .deb and .rpm on Linux and the .msi on Windows (DEPLOYMENT → Releasing the
            // desktop app); the .dmg waits for a Mac.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Kadans"
            packageVersion = providers.gradleProperty("kadans.version").get() // one version for every app (gradle.properties)
            description = "Tasks, focus and budget, in rhythm."
            vendor = "Kadans"
            copyright = "© 2026 Kadans"
            // The runtime is cut down to what the app uses. Beyond java.desktop's modules, the Linux tray's D-Bus
            // (dbus-java) needs the user id for its login (jdk.security.auth) and the Unix socket's options (jdk.net).
            modules("jdk.security.auth", "jdk.net")
            // Drawn by branding/generate.py. macOS needs an .icns, made on a Mac with the DMG (not yet).
            linux {
                iconFile.set(project.file("icons/kadans.png"))
                menuGroup = "Office" // the desktop menu's section for planners and calendars ("Unknown" without it)
                appCategory = "office"
                debMaintainer = "support@kadansplanning.com" // jpackage writes "Kadans <…>" (the vendor); "Unknown <Unknown>" without it
                rpmLicenseType = "Proprietary"
            }
            windows {
                iconFile.set(project.file("icons/kadans.ico"))
                // The same for every release, forever: it is how Windows knows a new installer replaces the Kadans
                // already installed, instead of putting a second one beside it.
                upgradeUuid = "8328c6d4-50b1-407f-a10e-78a9168da639"
                // A Start menu entry and a desktop shortcut; installed for the person, so no administrator prompt.
                menu = true
                menuGroup = "Kadans"
                shortcut = true
                perUserInstall = true
            }
        }
    }
}
