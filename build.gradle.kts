plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.2.2"
    id("io.freefair.lombok") version "9.0.0"
}

group = "dev.giqnt.rbw.hook"

repositories {
    mavenCentral()
    maven {
        name = "spigot"
        url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    }
    maven {
        url = uri("https://repo.extendedclip.com/releases/")
    }
}

dependencies {
    api("org.jspecify:jspecify:1.0.0")
    compileOnly("org.spigotmc:spigot-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(fileTree("libs") { include("*.jar") })
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.12.1")
    compileOnly("me.clip:placeholderapi:2.11.6")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks {
    jar {
        archiveClassifier.set("not-shaded")
    }

    shadowJar {
        archiveClassifier.set(null)
        enableAutoRelocation.set(true)
        relocationPrefix.set("${project.group}.libs")
        minimize()
    }
}
