pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
    plugins {
        id("com.android.application") version "8.9.1"
        id("org.jetbrains.kotlin.android") version "2.1.20"
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "Camera-movie"

// 画像処理エンジン（純 Kotlin/JVM。PC 上でもテスト・現像できる）
include(":core")

// Android アプリ本体は Android SDK がある環境（Android Studio / GitHub Actions）でのみ読み込む
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
if (hasAndroidSdk) {
    include(":app")
}

// SDK が無い環境で app のソースを android-all(Robolectric) に対して型チェックするための補助モジュール
if (providers.gradleProperty("androidTypecheck").isPresent) {
    include(":typecheck")
}
