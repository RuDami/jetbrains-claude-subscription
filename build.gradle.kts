import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.rudami"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

/**
 * JetBrains AI Assistant is NOT bundled inside the IDE installation — it is installed per-IDE
 * under the config directory and updates on its own cadence, so `bundledPlugin("com.intellij.ml.llm")`
 * cannot find it. Discovered here rather than hard-coded so the checked-in build carries nobody's
 * home directory; override with `aiAssistantPluginPath` in `gradle.properties` when the guess is wrong.
 */
fun discoverAiAssistant(): String {
    val home = File(System.getProperty("user.home"))
    val configRoots = listOf(
        File(home, "Library/Application Support/JetBrains"),
        File(home, ".local/share/JetBrains"),
        File(home, ".config/JetBrains"),
    )

    return configRoots
        .filter { it.isDirectory }
        .flatMap { it.listFiles()?.toList().orEmpty() }
        .map { File(it, "plugins/ml-llm") }
        .filter { File(it, "lib/modules/intellij.ml.llm.chat.jar").isFile }
        // Newest IDE wins: directory names sort as WebStorm2026.1 < WebStorm2026.2.
        .maxByOrNull { it.parentFile.parentFile.name }
        ?.absolutePath
        ?: error(
            "Could not find the AI Assistant plugin. Install it in a 2026.2 IDE, or set " +
                "aiAssistantPluginPath in gradle.properties.",
        )
}

fun discoverPlatform(): String =
    listOf("/Applications/WebStorm.app/Contents", "/Applications/IntelliJ IDEA.app/Contents")
        .map(::File)
        .firstOrNull { it.isDirectory }
        ?.absolutePath
        ?: error("Could not find a local JetBrains IDE. Set platformLocalPath in gradle.properties.")

val platformPath: Provider<String> = providers.gradleProperty("platformLocalPath")
    .orElse(providers.provider { discoverPlatform() })

val aiAssistantPath: Provider<String> = providers.gradleProperty("aiAssistantPluginPath")
    .orElse(providers.provider { discoverAiAssistant() })

dependencies {
    intellijPlatform {
        local(platformPath)
        localPlugin(aiAssistantPath)
    }

    // `AgentIconService` lives in a module jar under the plugin's `lib/modules/`, which is
    // not on the classpath `localPlugin` contributes. compileOnly by nature: AI Assistant
    // supplies the class at runtime and `plugin.xml` declares the dependency, so the IDE
    // refuses to load us without it.
    compileOnly(files(aiAssistantPath.map { "$it/lib/modules/intellij.ml.llm.chat.jar" }))
}

// No jvmToolchain(): build on whatever JDK Gradle runs on (the JetBrains Runtime, 25).
// The target is 25 rather than something older because the platform's own Kotlin inline
// functions — `BaseState.enum()` among them — are compiled at 25, and Kotlin refuses to
// inline newer bytecode into an older target. Set per task rather than through the
// `kotlin { compilerOptions { } }` extension, which gets overwritten later in the build.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

intellijPlatform {
    pluginConfiguration {
        // `AgentIconService` is internal API of the AI Assistant plugin, not a published
        // contract. Pin to the 262 branch rather than claim untested forward compatibility.
        ideaVersion {
            sinceBuild.set("262")
            untilBuild.set("262.*")
        }
    }

    buildSearchableOptions.set(false)
}
