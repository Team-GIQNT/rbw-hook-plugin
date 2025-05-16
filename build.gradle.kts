plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.0.0-beta13"
    id("io.freefair.lombok") version "8.13.1"
}

group = "dev.giqnt.rbw.hook"
version = "1.0"

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
    implementation("com.google.code.gson:gson:2.12.1")
    compileOnly("me.clip:placeholderapi:2.11.6")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks {
    compileJava {
        options.release = 17
    }

    build {
        finalizedBy(shadowJar)
    }

    shadowJar {
        enableRelocation = true
        relocationPrefix = "${project.group}.libs"
        minimize()

        doLast {
            copy {
                from(shadowJar.get().archiveFile)
                into(layout.buildDirectory.dir("result").get())
                rename { "${rootProject.name}.jar" }
            }
        }
    }
}
