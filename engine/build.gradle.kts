import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
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
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// サウンドフォントから「生音フュージョン」の音源ファイルを作る（入力・出力は --args で渡す）
tasks.register<JavaExec>("makeBandBank") {
    group = "ekifu"
    description = "GeneralUser GS から生音フュージョンで使う楽器だけを抜き出す"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("jp.fmt.ekifu.engine.sf2.MakeBank")
}
