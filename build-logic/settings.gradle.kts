// ============================================================================
// build-logic 用の settings.gradle.kts
//
// build-logic はルートビルドとは独立した複合ビルド (composite build) である。
// ルートの gradle/libs.versions.toml をそのままファイル参照することで、
// バージョンカタログをルートと共有し、「バージョンの単一情報源」を保つ。
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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
