plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("io.ktor.plugin") version "3.6.0"
    application
}

group = "com.triplethreats.masteria"
version = "1.0.0"

val ktorVersion = "3.6.0"

application {
    mainClass.set("com.triplethreats.masteria.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-rate-limit:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.mindrot:jbcrypt:0.4")
    // Verifies Firebase ID tokens against Google's public keys (no service account needed)
    implementation("com.auth0:jwks-rsa:0.24.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:5.5.1")
    implementation("org.mongodb:bson-kotlinx:5.5.1")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
}

// Bundle the vetted content (tracks + question bank) into the jar under content/
tasks.processResources {
    val rootContent = file("../content")
    val localContent = file("content")
    when {
        rootContent.exists() -> from(rootContent) { into("content") }
        localContent.exists() -> from(localContent) { into("content") }
    }
}

ktor {
    fatJar {
        archiveFileName.set("masteria-backend.jar")
    }
}
