import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.rudami"
version = "0.2.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

fun discoverPlatform(): String? =
    listOf("/Applications/WebStorm.app/Contents", "/Applications/IntelliJ IDEA.app/Contents")
        .map(::File)
        .firstOrNull { it.isDirectory }
        ?.absolutePath

/**
 * Build against a locally installed IDE when there is one — it is already on disk and saves
 * downloading a gigabyte — and fall back to the published artifact otherwise, which is what
 * lets this build run on CI. Override with `platformLocalPath` in `gradle.properties`.
 */
val platformPath: Provider<String> = providers.gradleProperty("platformLocalPath")
    .orElse(providers.provider { discoverPlatform() ?: "" })

/**
 * A compileOnly source set holding a stand-in for AI Assistant's `AgentIconService`.
 *
 * That interface is internal API: it ships inside a module jar in an IDE installation, not in
 * any artifact a build can resolve. Depending on a local AI Assistant install made the build
 * unreproducible and impossible on CI, so the one-method interface is declared locally and
 * never packaged — the real class arrives at runtime from the AI Assistant plugin.
 */
val stub: SourceSet = sourceSets.create("stub")

dependencies {
    intellijPlatform {
        val local = platformPath.get()
        if (local.isNotEmpty()) local(local) else webstorm("2026.2")
    }

    compileOnly(stub.output)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
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
        // No upper bound. The one piece of internal AI Assistant API this plugin touches —
        // `AgentIconService` — lives in an optional descriptor now (claude-agent-icon.xml),
        // so a future IDE that renames or drops it costs the agent its icon rather than the
        // ability to install. Everything else here is public platform API.
        ideaVersion {
            sinceBuild.set("262")
            untilBuild.set(provider { null })
        }

        changeNotes.set(
            """
            <b>0.2.1</b>
            <ul>
              <li>Fixed the settings page hanging on "Loading adapter versions".</li>
              <li>Adapters a running chat still uses are no longer deleted by automatic
                  cleanup, and can be deleted deliberately.</li>
              <li>Config files are written atomically, so the IDE and the adapter never read
                  one half-written.</li>
              <li>Permission rules approved in the chat are merged instead of overwritten.</li>
            </ul>

            <b>0.2.0</b>
            <ul>
              <li>Pick any published adapter version from a list and switch to it; older
                  builds stay on disk, so choosing one is a rollback.</li>
              <li>A settings page for the project's Claude Code permissions — allow, ask and
                  deny rules and the default mode. Rules approved in the chat are merged
                  rather than overwritten.</li>
              <li>Add and remove the agent outright, choose the Node.js interpreter from the
                  ones found on the machine, and point npm at a mirror.</li>
            </ul>

            <b>0.1.0</b>
            <ul>
              <li>Installs <code>@agentclientprotocol/claude-agent-acp</code> with npm and
                  registers it as a local ACP agent without <code>--hide-claude-auth</code>,
                  so a Claude Pro/Max subscription can be used to log in.</li>
              <li>Checks the npm registry for adapter updates and installs them without
                  restarting the IDE; keeps the previous version for rollback.</li>
              <li>Strips <code>ANTHROPIC_API_KEY</code> and related variables from the agent's
                  environment so usage is not silently billed to the API.</li>
            </ul>
            """.trimIndent(),
        )
    }

    buildSearchableOptions.set(false)

    // JetBrains runs the Plugin Verifier on every Marketplace upload; running it here means
    // finding out before the upload rather than after. Pinned to one IDE because with no
    // upper build bound `recommended()` would fetch every release it can find.
    pluginVerification {
        ides {
            select {
                types.add(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.WebStorm)
                channels.add(org.jetbrains.intellij.platform.gradle.models.ProductRelease.Channel.RELEASE)
                sinceBuild.set("262")
                untilBuild.set("262.*")
            }
        }
    }
}
