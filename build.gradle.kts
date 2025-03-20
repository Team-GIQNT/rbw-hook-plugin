plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.giqnt.rbw.hook.bedwars1058"
version = "1.0"

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        name = "spigot"
        url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    }
    maven {
        name = "andrei1058"
        url = uri("https://repo.andrei1058.dev/releases/")
    }
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/snapshots")
    }
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/central")
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.andrei1058.bedwars:bedwars-api:25.2")
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

