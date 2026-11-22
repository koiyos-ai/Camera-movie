import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    // PC 上で DNG をフィルム現像する CLI（動作確認・チューニング用）
    mainClass.set("filmlab.core.cli.CliKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "4g"
}
