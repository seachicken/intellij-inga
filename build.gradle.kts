plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.16.1"
}

group = "inga"
version = "0.5.2-beta.0"

repositories {
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2023.2")
    type.set("IC") // Target IDE Platform

    plugins.set(
        listOf(
            // If you want to try the nightly build, the version is "{version}@nightly"
            // https://plugins.jetbrains.com/api/plugins/23257/updates?channel=nightly&size=1
            "com.redhat.devtools.lsp4ij:0.6.0-20240927-013153@nightly",
        )
    )
}

dependencies {
    implementation("com.github.docker-java:docker-java-core:3.3.6")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.3.6")
}

configurations {
    runtimeClasspath {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
        channels.set(listOf(project.version.toString().substringAfter('-', "").substringBefore('.').ifEmpty { "default" }))
    }
}
