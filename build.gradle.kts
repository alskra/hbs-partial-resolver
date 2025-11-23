plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij") version "1.16.0"
}

group = "com.example.hbsresolver"
version = "0.1.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2025.2.4")
    type.set("PS")
    downloadSources.set(false)
}

// --- ДОБАВЛЕНО: обеспечить корректную совместную компиляцию Java + Kotlin ---
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17)) // та же Java, что и IDE 2025.x
    }
}

dependencies {
    implementation(kotlin("stdlib"))

    // --- ДОБАВЛЕНО: строгая nullability из Java ---
    compileOnly("org.jetbrains:annotations:24.0.1")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"      // требуется для платформы IntelliJ 2025.x
        freeCompilerArgs += listOf(
            "-Xjsr305=strict"   // строгая обработка @NotNull/@Nullable из Java
        )
    }
}

// Параметры сборки плагина
tasks.buildPlugin {
    archiveBaseName.set("hbs-partial-resolver")
    archiveVersion.set("0.1.0")
}

tasks.named<org.jetbrains.intellij.tasks.BuildSearchableOptionsTask>("buildSearchableOptions") {
    enabled = false
}
