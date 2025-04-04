plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.giqnt.rbw.hook.bedwars1058"
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

tasks {
    build {
        finalizedBy(shadowJar)
    }

    shadowJar {
        isEnableRelocation = true

        doLast {
            copy {
                from(shadowJar.get().archiveFile)
                into(layout.buildDirectory.dir("result").get())
                rename { "${rootProject.name}.jar" }
            }
        }
    }
}

