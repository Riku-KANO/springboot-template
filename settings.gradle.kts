// ============================================================================
// ルートの settings.gradle.kts
//
// build-logic を複合ビルド (includeBuild) として取り込み、7つのモジュールを
// include する。実際のビルドロジックは一切ここに書かない。
// ============================================================================

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "springboot-template"

includeBuild("build-logic")

include(
    "domain",
    "application",
    "adapter-persistence",
    "adapter-web",
    "adapter-messaging",
    "batch",
    "bootstrap",
)
