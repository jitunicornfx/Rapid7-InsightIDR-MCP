import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

group = "com.jitunicornfx.insightidr"
version = "0.1.0"

dependencies {
    implementation(platform(libs.ktor.bom))

    implementation(libs.mcp.kotlin.server)

    // HTTP client used to talk to the InsightIDR REST API.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // Only required for the optional --http (Streamable HTTP / SSE) transport.
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // slf4j-simple logs to stderr by default, which keeps stdout clean for the
    // MCP JSON-RPC channel when running over stdio.
    implementation(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(libs.mcp.kotlin.client)
}

application {
    mainClass.set("com.jitunicornfx.insightidr.mcp.MainKt")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        javaParameters = true
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("rapid7-insightidr-mcp")
    archiveClassifier.set("all")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
}
