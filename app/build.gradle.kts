import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "filmlab.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.cameramovie.filmlab"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // 個人利用のサイドロード用。CI ビルドでも署名が変わらないよう固定の開発用鍵を使う（秘密情報ではない）
    signingConfigs {
        create("personal") {
            storeFile = file("filmlab-dev.keystore")
            storePassword = "filmlab"
            keyAlias = "filmlab"
            keyPassword = "filmlab"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        // 画素処理が重いので、最適化される release（debuggable=false）で使う
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        // 個人用ビルドなので lint で失敗させない
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
}
