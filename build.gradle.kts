plugins {
    java
    alias(libs.plugins.lavalink)
    kotlin("jvm")
}

group = "lavabili"
version = "1.0.0"

lavalinkPlugin {
    name = "lavabili-plugin"
    apiVersion = libs.versions.lavalink.api
    serverVersion = libs.versions.lavalink.server
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}

dependencies {
    // add your dependencies here
    implementation(kotlin("stdlib-jdk8"))
}
repositories {
    mavenCentral()
}