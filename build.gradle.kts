import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.serialization)
    alias(libs.plugins.gradleIntelliJPlugin) // Gradle IntelliJ Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.rdgen)
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

val rdLibDirectory: () -> File = { file("${tasks.setupDependencies.get().idea.get().classes}/lib/rd") }
extra["rdLibDirectory"] = rdLibDirectory

// Configure project's dependencies
repositories {
    mavenCentral()
    maven { setUrl("https://cache-redirector.jetbrains.com/maven-central") }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}

// Set the JVM language level used to build the project. Use Java 11 for 2020.3+, and Java 17 for 2022.2+.
kotlin {
    @Suppress("UnstableApiUsage")
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(17)
        vendor = JvmVendorSpec.JETBRAINS
    }
}

// Configure Gradle IntelliJ Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    pluginName = properties("pluginName")
    version = properties("platformVersion")
    type = properties("platformType")

    // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file.
    plugins = properties("platformPlugins").map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = properties("pluginRepositoryUrl")
}

// Configure Gradle Qodana Plugin - read more: https://github.com/JetBrains/gradle-qodana-plugin
qodana {
    cachePath = provider { file(".qodana").canonicalPath }
    reportPath = provider { file("build/reports/inspections").canonicalPath }
    saveReport = true
    showReport = environment("QODANA_SHOW_REPORT").map { it.toBoolean() }.getOrElse(false)
}

tasks {
    wrapper {
        gradleVersion = properties("gradleVersion").get()
    }

    configure<com.jetbrains.rd.generator.gradle.RdGenExtension> {
        val modelDir = projectDir.resolve("protocol/src/main/kotlin/model/sessionHost")
        val pluginSourcePath = projectDir.resolve("src")
        val ktOutput = pluginSourcePath.resolve("main/kotlin/me/rafaelldi/aspire/generated")
        val csOutput = pluginSourcePath.resolve("dotnet/aspire-session-host/Generated")

        verbose = true
        classpath({
            rdLibDirectory().resolve("rider-model.jar").canonicalPath
        })
        sources(modelDir)
        hashFolder = "$rootDir/build/rdgen/rider"
        packages = "model.sessionHost"

        generator {
            language = "kotlin"
            transform = "asis"
            root = "model.sessionHost.AspireSessionHostRoot"
            directory = ktOutput.canonicalPath
        }

        generator {
            language = "csharp"
            transform = "reversed"
            root = "model.sessionHost.AspireSessionHostRoot"
            directory = csOutput.canonicalPath
        }
    }

    val dotnetBuildConfiguration = properties("dotnetBuildConfiguration").get()
    val compileDotNet by registering {
        doLast {
            exec {
                executable("dotnet")
                args("build", "-c", dotnetBuildConfiguration, "/clp:ErrorsOnly", "aspire-plugin.sln")
            }
        }
    }
    val publishSessionHost by registering {
        dependsOn(compileDotNet)
        doLast {
            exec {
                executable("dotnet")
                args(
                    "publish",
                    "src/dotnet/aspire-session-host/aspire-session-host.csproj",
                    "--configuration", dotnetBuildConfiguration
                )
            }
        }
    }

    buildPlugin {
        dependsOn(publishSessionHost)
    }

    prepareSandbox {
        dependsOn(publishSessionHost)

        val outputFolder = file("$projectDir/src/dotnet/aspire-plugin/bin/$dotnetBuildConfiguration")
        val dllFiles = listOf(
            "$outputFolder/aspire-plugin.dll",
            "$outputFolder/aspire-plugin.pdb"
        )

        for (f in dllFiles) {
            from(f) { into("${rootProject.name}/dotnet") }
        }

        doLast {
            for (f in dllFiles) {
                val file = file(f)
                if (!file.exists()) throw RuntimeException("File \"$file\" does not exist")
            }
        }

        from("$projectDir/src/dotnet/aspire-session-host/bin/$dotnetBuildConfiguration/publish") {
            into("${rootProject.name}/aspire-session-host")
        }
    }

    runPluginVerifier {
        ideVersions.set(
            properties("pluginVerifierIdeVersions").get().split(',').map(String::trim).filter(String::isNotEmpty)
        )
    }

    patchPluginXml {
        version = properties("pluginVersion")
        sinceBuild = properties("pluginSinceBuild")
        untilBuild = properties("pluginUntilBuild")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        pluginDescription = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = properties("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    // Configure UI tests plugin
    // Read more: https://github.com/JetBrains/intellij-ui-test-robot
    runIdeForUiTests {
        systemProperty("robot-server.port", "8082")
        systemProperty("ide.mac.message.dialogs.as.sheets", "false")
        systemProperty("jb.privacy.policy.text", "<!--999.999-->")
        systemProperty("jb.consents.confirmation.enabled", "false")
    }

    signPlugin {
        certificateChain = environment("CERTIFICATE_CHAIN")
        privateKey = environment("PRIVATE_KEY")
        password = environment("PRIVATE_KEY_PASSWORD")
    }

    publishPlugin {
        dependsOn("patchChangelog")
        token = environment("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = properties("pluginVersion").map { listOf(it.split('-').getOrElse(1) { "default" }.split('.').first()) }
    }
}
