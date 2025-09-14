plugins {
    java
    alias(libs.plugins.lavalink)
    kotlin("jvm")
}

group = "com.github.parrotxray.lavabili"
version = "1.3.0"

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
    compileOnly(libs.lavalink.server)
    compileOnly(libs.lavaplayer)

}
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") } // For LavaPlayer dependencies
}