import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android SDK が無い環境で app のソースを android-all(Robolectric) に対して型チェックするだけのモジュール。
// 使い方: ./gradlew -PandroidTypecheck :typecheck:compileKotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    sourceSets["main"].kotlin.srcDir("../app/src/main/java")
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("org.robolectric:android-all:16-robolectric-13921718")
    implementation(project(":core"))
}
