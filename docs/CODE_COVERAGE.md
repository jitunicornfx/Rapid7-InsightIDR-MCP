# Unit Test Code Coverage

This project measures unit-test code coverage with **[JaCoCo](https://www.jacoco.org/jacoco/)**, the
standard coverage library for JVM (Java/Kotlin) projects, driven through Gradle. JaCoCo instruments the
compiled bytecode while the test task runs, then emits HTML (for humans) and XML (for tools such as
SonarQube).

## TL;DR

```bash
# Windows
gradlew.bat test jacocoTestReport

# macOS / Linux
./gradlew test jacocoTestReport
```

Then open the HTML report:

```
build/reports/jacoco/test/html/index.html
```

## What's already set up

Coverage is wired into the Gradle build — you don't need to install anything beyond a JDK.

- The `jacoco` plugin is applied in [`build.gradle.kts`](../build.gradle.kts), pinned to
  `toolVersion = "0.8.13"` (needed to instrument/run on current JDKs — this project builds on JDK 21+
  and has been verified on JDK 25).
- `test` is `finalizedBy(jacocoTestReport)`, so **every test run refreshes the coverage report**.
- `jacocoTestReport` produces both **XML** (`xml.required = true`, consumed by SonarQube) and **HTML**
  (`html.required = true`).
- A `jacocoTestCoverageVerification` task exists with a `minimum` of `0.00` — a placeholder gate you can
  raise once meaningful tests exist (see [Enforcing a threshold](#enforcing-a-minimum-threshold)).
- SonarQube is pointed at the JaCoCo XML via `sonar.coverage.jacoco.xmlReportPaths`.

Tests live under `src/test/kotlin/` mirroring the main package (`com.jitunicornfx.insightidr.mcp`),
run on the JUnit Platform (`useJUnitPlatform()`) with `kotlin.test` assertions.

## Prerequisites

- **JDK 21 or newer** (the toolchain here uses JDK 25). Verify with `java -version`.
- The Gradle **wrapper** (`gradlew` / `gradlew.bat`) — no separate Gradle install required.

## Running coverage

| Goal | Command |
|------|---------|
| Run tests + generate the report | `./gradlew test jacocoTestReport` |
| Just run tests (report auto-refreshes) | `./gradlew test` |
| Generate the report (runs tests first) | `./gradlew jacocoTestReport` |
| Re-run from a clean state | `./gradlew clean test jacocoTestReport` |

> On Windows use `gradlew.bat`. If a previous run was cached, add `--rerun-tasks` to force execution.

### Where the reports land

| Format | Path | Use |
|--------|------|-----|
| HTML | `build/reports/jacoco/test/html/index.html` | Open in a browser; drill down package → class → line. |
| XML  | `build/reports/jacoco/test/jacocoTestReport.xml` | Machine-readable; consumed by SonarQube/CI. |
| JUnit results | `build/test-results/test/*.xml` | Per-test pass/fail details. |

### Reading the HTML report

Open `index.html`. JaCoCo reports several **counters**; the ones to watch:

- **Instructions / Lines** — how much code executed. This is the headline number.
- **Branches** — how many `if`/`when`/`?:` branches were taken (catches untested edge cases).
- **Methods / Classes** — breadth of what was touched at all.

Green = covered, yellow = partially covered branch, red = not executed. Click into a class to see
line-by-line highlighting.

## Current baseline

Current overall coverage is roughly **98% line / 97% method / 62% branch** across ~133 tests.
Every source file — all v1/v2 IDR tool domains, all Log Search tool domains, `Rapid7Client`,
`Config`, `ToolSupport`, `LogSearchSupport`, and `McpServerFactory` (verified by listing the full
129-tool inventory through an in-process MCP client) — sits at **98–100% line coverage**, with one
deliberate exception:

`Main.kt` sits around **38%** by design: the Clikt command (option parsing, the `run()` dispatch, and
the config-error path) is tested via injected seams, but `runStdio`/`runHttp`/`runServer` start real,
blocking servers and are not unit-tested. Branch coverage trails line coverage because each tool has
many independent optional parameters; exercising every combination isn't necessary.

## Adding more tests

Put tests in `src/test/kotlin/com/jitunicornfx/insightidr/mcp/…`, one `…Test.kt` per unit under test.

**Pure logic (no I/O)** — easy wins, cover these first:

- `Config` / `Region` — env parsing, region → base URL, validation (see `ConfigTest.kt`).
- `ToolSupport` — `query()`, `seg()`, argument accessors, `ApiResponse.toToolResult()` (see
  `ToolSupportTest.kt`).

**HTTP-dependent code** (`Rapid7Client` and the `tools/` handlers) needs a stubbed HTTP layer rather
than real network calls. Use **Ktor's `MockEngine`** to assert the request each tool builds (method,
path, query keys like `assignee.email` / `multi-customer`, and JSON body) and to feed canned responses.
Sketch:

```kotlin
val engine = MockEngine { request ->
    // assert request.method, request.url.encodedPath, request.url.parameters, request body…
    respond(
        content = """{"data":[]}""",
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
```

To make `Rapid7Client` testable this way, allow injecting the Ktor `HttpClient` (or the engine) so a
test can pass a `MockEngine`-backed client. That one seam unlocks coverage of all ~50 tool handlers.

Registration wiring can be covered with an in-process MCP client (`kotlin-sdk-client`, already a
`testImplementation` dependency): start the server over an in-memory/stdio transport and assert
`tools/list` returns the expected tools.

## Enforcing a minimum threshold

The `jacocoTestCoverageVerification` task is defined but not enforced. To make CI fail below a bar:

1. Raise the limit in [`build.gradle.kts`](../build.gradle.kts):

   ```kotlin
   tasks.jacocoTestCoverageVerification {
       violationRules {
           rule {
               limit {
                   counter = "LINE"
                   minimum = "0.70".toBigDecimal() // e.g. 70%
               }
           }
       }
   }
   ```

2. Wire it into `check` so `./gradlew check` (and CI) runs it:

   ```kotlin
   tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
   ```

Start low and ratchet up so the gate never blocks unrelated work.

## Excluding noise (optional)

Coverage counts every class with bytecode, including glue that isn't worth testing (e.g. the `Main`
entry point). To exclude such classes from the **report and the gate**, filter `classDirectories`:

```kotlin
tasks.jacocoTestReport {
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) { exclude("**/MainKt.class") }
        }),
    )
}
```

Keep exclusions minimal and intentional — excluding code doesn't make it tested, it just hides it.

## SonarQube integration

The build sets:

```kotlin
sonar {
    properties {
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.path,
        )
    }
}
```

So a scan is a two-step sequence — generate coverage first, then hand it to Sonar:

```bash
./gradlew test jacocoTestReport sonar \
  -Dsonar.host.url=https://your-sonarqube \
  -Dsonar.token=YOUR_TOKEN \
  -Dsonar.projectKey=rapid7-insightidr-mcp
```

SonarQube reads the JaCoCo XML (it does not run coverage itself), so `jacocoTestReport` must run in the
same invocation, before `sonar`. Never commit the Sonar token — pass it via env/CI secret.

## Continuous integration

A minimal CI job:

```bash
./gradlew clean test jacocoTestReport            # unit tests + coverage
# optionally publish build/reports/jacoco/test/html as an artifact
# optionally: ./gradlew jacocoTestCoverageVerification   # once a threshold is set
```

The XML report and JUnit results under `build/` are the artifacts most CI systems ingest for trend
graphs and PR annotations.
