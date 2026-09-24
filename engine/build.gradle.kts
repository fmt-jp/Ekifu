plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Android（minSdk 26）からも使う純 Kotlin モジュール。Android に依存しないので PC 上でテスト・試聴できる
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("renderDemoWav") {
    group = "application"
    description = "デモ再生を engine/build/demo.wav に書き出す"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("jp.fmt.ekifu.engine.tools.RenderDemoKt")
    args(layout.buildDirectory.file("demo.wav").get().asFile.absolutePath)
}
