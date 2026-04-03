import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.sonarqube") version "7.2.3.7755"
}

group = "com.agentpulse"
version = "0.1.0"

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.components.resources)
}

compose.desktop {
    application {
        mainClass = "com.agentpulse.MainKt"
        if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            jvmArgs("-Dapple.awt.UIElement=true")
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "agent-pulse"
            packageVersion = "1.0.0"

            macOS {
                bundleID = "com.agentpulse.app"
                minimumSystemVersion = "12.0"
                infoPlist {
                    //noinspection SpellCheckingInspection
                    extraKeysRawXml = """
                        <key>LSUIElement</key>
                        <true/>
                    """.trimIndent()
                }
            }
        }
    }
}

sonar {
    properties {
        property("sonar.projectKey", "szgergo_agent-pulse")
        property("sonar.organization", "agent-pulse")
    }
}

