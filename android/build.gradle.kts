plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; declaring KGP here only pins the Kotlin version it uses.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    // Applied by :app only when app/google-services.json exists (see app/build.gradle.kts).
    id("com.google.gms.google-services") version "4.5.0" apply false
}
