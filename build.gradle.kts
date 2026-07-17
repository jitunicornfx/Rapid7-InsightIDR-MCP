import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    id ("org.sonarqube") version "7.3.1.8318"
    application
    jacoco
}

group = "com.jitunicornfx.insightidr"
version = "0.1.4"

dependencies {
    implementation(platform(libs.ktor.bom))

    implementation(libs.mcp.kotlin.server)

    // Command-line interface.
    implementation(libs.clikt)

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

    // Ktor MockEngine lets tests drive the tool handlers without real network calls.
    testImplementation("io.ktor:ktor-client-mock")

    // JUnit Platform engine so `useJUnitPlatform()` can actually discover and run tests.
    // JUnit 6 unifies platform/jupiter/vintage under one 6.x version (baseline Java 17+).
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    // Pin the launcher to the same 6.x line so Gradle's test worker matches the engine.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

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
    // Always refresh the coverage report after the tests run.
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    // 0.8.15+ is required to instrument/run under recent JDKs (this project builds on JDK 21+).
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)   // machine-readable; consumed by SonarQube
        html.required.set(true)  // human-readable report
        csv.required.set(false)
    }
}

// Optional coverage gate. Raise `minimum` once tests exist to fail the build below a threshold.
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.00".toBigDecimal()
            }
        }
    }
}

// Point SonarQube at the JaCoCo XML report.
sonar {
    properties {
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.path,
        )
    }
}

tasks.shadowJar {
    archiveBaseName.set("rapid7-insightidr-mcp")
    archiveClassifier.set("all")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
}
