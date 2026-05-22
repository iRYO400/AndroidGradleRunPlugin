import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "com.androidefficiency.plugin"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        androidStudio(providers.gradleProperty("platformVersion").get())
        bundledPlugin("org.jetbrains.android")
        pluginVerifier()
        instrumentationTools()
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.androidefficiency.plugin"
        name = "Android Efficiency — Fast Deploy"
        version = providers.gradleProperty("pluginVersion").get()

        description = """
            Fast build and deploy Android apps using terminal commands.
            Replaces the slow Android Studio Run button with optimized Gradle/Android CLI commands.
            Supports customizable flags, build flavors, and persists settings between IDE restarts.
        """.trimIndent()

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild").get()
            untilBuild = providers.gradleProperty("pluginUntilBuild").get()
        }

        vendor {
            name = "AndroidEfficiency"
            url = "https://github.com/AndroidEfficiency"
        }
    }

    pluginVerification {
        ides {
            ide("AI-2024.2.1.12")  // Ladybug Patch 3 — floor
            ide("AI-2024.3.1.14")  // Meerkat Patch 14
            ide("AI-2025.3.4.7")   // Panda 4 Patch 1 — reported failure case
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.11"
        distributionType = Wrapper.DistributionType.BIN
    }

    compileKotlin {
        kotlinOptions.jvmTarget = "17"
    }

    compileTestKotlin {
        kotlinOptions.jvmTarget = "17"
    }
}
