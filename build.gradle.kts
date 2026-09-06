plugins {
    java
    alias(libs.plugins.lavalink)
    kotlin("jvm")
}

group = "com.github.parrotxray.lavabili"
version = "1.4.1"

lavalinkPlugin {
    name = "lavabili-plugin"
    apiVersion = libs.versions.lavalink.api
    serverVersion = libs.versions.lavalink.server
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

    // Bundled into the plugin jar so LavaSearch/LavaLyrics REST support works
    // without requiring users to install those plugins separately.
    implementation(libs.lavasearch)
    implementation(libs.lavalyrics)

    // SearchManagerConfiguration/LyricsManagerConfiguration - the interfaces LavabiliPlugin
    // implements to register with Lavalink's REST layer - live in these separate
    // "plugin-api" artifacts, not in lavasearch/lavalyrics itself.
    implementation(libs.lavasearch.plugin.api)
    implementation(libs.lavalyrics.plugin.api)
}
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") } // For LavaPlayer dependencies
    maven { url = uri("https://maven.topi.wtf/releases") } // For LavaSearch/LavaLyrics
    maven { url = uri("https://maven.lavalink.dev/releases") } // For LavaSearch/LavaLyrics transitive deps
}