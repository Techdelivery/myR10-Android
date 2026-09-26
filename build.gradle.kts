import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

// Root project — all plugins declared here with apply false, applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.protobuf) apply false
    // Style gate. Declared here, applied to every project in the block below so a
    // new module inherits the standard instead of having to opt in.
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// Captured on the root receiver: the `libs` accessor is not visible from inside an
// `allprojects { }` lambda, so resolve the tool versions once, here.
val ktlintToolVersion = libs.versions.ktlint.get()
val detektToolVersion = libs.versions.detekt.get()

// ---------------------------------------------------------------------------
// Coding standard enforcement.
//
// docs/CODING_STANDARDS.md is the prose; this is the machinery.
//   ./gradlew ktlintFormat   auto-fix formatting
//   ./gradlew ktlintCheck    formatting gate (ktlint, driven by .editorconfig)
//   ./gradlew detekt         code-smell gate (config/detekt/detekt.yml)
//
// Both are wired into `check` below, so `./gradlew build` cannot go green with
// the standard unmet. Generated sources are excluded — we do not format protobuf
// codegen output.
// ---------------------------------------------------------------------------
allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<KtlintExtension> {
        version.set(ktlintToolVersion)
        filter {
            // Generated protobuf Kotlin is registered as its own source root, so a
            // `**/build/**` pattern (matched relative to that root) never sees the
            // `build/` prefix. The Spec catches it by absolute path; the pattern
            // covers the kotlin-script tasks, whose root IS the project dir.
            exclude("**/build/**")
            exclude(Spec { it.file.absolutePath.contains("${File.separator}build${File.separator}") })
        }
        reporters {
            reporter(ReporterType.HTML)
            // GitHub code-scanning ingestable format.
            reporter(ReporterType.SARIF)
        }
    }

    extensions.configure<DetektExtension> {
        toolVersion = detektToolVersion
        // config/detekt/detekt.yml carries only the deltas from detekt's defaults.
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // One baseline per module: `detektBaseline` overwrites the file it is given,
        // so a single shared baseline would lose the other module's entries.
        baseline = rootProject.file("config/detekt/${project.name}-baseline.xml")
        parallel = true
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(true)
        }
    }

    // Make the standard part of the normal build rather than an opt-in.
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn("ktlintCheck", "detekt")
    }
}
