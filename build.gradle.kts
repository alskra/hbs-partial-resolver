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

tasks.buildPlugin {
    archiveBaseName.set("hbs-partial-resolver")
    archiveVersion.set("0.1.0")
}

tasks.named<org.jetbrains.intellij.tasks.BuildSearchableOptionsTask>("buildSearchableOptions") {
    enabled = false
}