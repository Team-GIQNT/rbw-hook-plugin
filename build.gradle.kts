plugins {
    id("java")
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
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(fileTree("libs") { include("*.jar") })
    implementation("com.google.code.gson:gson:2.12.1")
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
