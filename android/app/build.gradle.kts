import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateLocaleCatalogTask : DefaultTask() {
    @get:Input
    abstract val localeTags: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val target = outputDirectory.file("values/locale_catalog.xml").get().asFile
        target.parentFile.mkdirs()
        target.writeText(buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<resources>")
            appendLine("    <string-array name=\"supported_locale_tags\">")
            localeTags.get().forEach { appendLine("        <item>$it</item>") }
            appendLine("    </string-array>")
            appendLine("</resources>")
        })
    }
}

abstract class VerifyPlayReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val configured: Property<Boolean>

    @TaskAction
    fun verifySigning() {
        check(configured.get()) { "Play release signing is not configured; set all RELEASE_* properties in local.properties." }
    }
}

/**
 * Copies R8's mapping.txt out of build/ after each minified release build, named by version
 * and the pg_map_id that release stack traces carry ("r8-map-id-<id>"), so a crash from a
 * sideloaded APK can still be retraced after the next build or `clean`.
 */
abstract class ArchiveR8MappingTask : DefaultTask() {
    @get:InputFile abstract val mapping: RegularFileProperty
    @get:Input abstract val versionLabel: Property<String>
    @get:Internal abstract val archiveDirectory: DirectoryProperty

    @TaskAction
    fun archive() {
        val source = mapping.get().asFile
        val mapId = source.useLines { lines ->
            lines.take(20).firstOrNull { it.startsWith("# pg_map_id: ") }?.removePrefix("# pg_map_id: ")?.trim()
        } ?: "unknown"
        val dir = archiveDirectory.get().asFile.apply { mkdirs() }
        val target = dir.resolve("${versionLabel.get()}-${mapId.take(16)}.txt")
        source.copyTo(target, overwrite = true)
        logger.lifecycle("R8 mapping archived: ${target.absolutePath} (pg_map_id $mapId)")
    }
}

val appVersionName = "1.0.8"

plugins {
    alias(libs.plugins.protobuf)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutLibraries)
//    alias(libs.plugins.hilt)
    id("kotlin-parcelize")
}

val props = Properties()
val localProperties = rootProject.file("local.properties")
if (localProperties.exists()) {
    localProperties.inputStream().use(props::load)
}

val hasReleaseSigning = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD"
).all { props.getProperty(it)?.isNotBlank() == true }

val appLocaleFolders = file("src/main/res").listFiles().orEmpty()
    .filter { folder ->
        folder.isDirectory && folder.name.startsWith("values-") &&
            folder.resolve("strings.xml").isFile
    }
    .map { it.name.removePrefix("values-") }
    .distinct()

android {
    lint {
        baseline = file("lint-baseline.xml")
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(props.getProperty("RELEASE_STORE_FILE"))
                storePassword = props.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = props.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = props.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }
    namespace = "io.automated.ventures.everypods"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.automated.ventures.everypods"
        minSdk = 33
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 138
        versionName = appVersionName
        buildConfigField("Boolean", "DIRCON_ENABLED", "false")
        manifestPlaceholders["appLabel"] = "EveryPods"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
            buildConfigField("Boolean", "PLAY_BUILD", "false")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
        debug {
            buildConfigField("Boolean", "PLAY_BUILD", "false")
            buildConfigField("Boolean", "DIRCON_ENABLED", "true")
            versionNameSuffix = "-debug"
        }
        create("playRelease") {
            initWith(getByName("release"))
            buildConfigField("Boolean", "PLAY_BUILD", "true")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
        create("playDebug") {
            initWith(getByName("debug"))
            buildConfigField("Boolean", "PLAY_BUILD", "true")
            versionNameSuffix = "-youshouldnothavethis"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
        localeFilters.addAll(listOf("en") + appLocaleFolders)
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets {
        getByName("main") {
            res.directories += "src/main/res-apple"
        }
    }

    ndkVersion = "30.0.14904198"
    bundle {
        language {
            enableSplit = false
        }
    }
}

// Keep picker choices and packaged resources derived from app translation folders.
val localeCatalogOutput = layout.buildDirectory.dir("generated/res/localeCatalog")
val generatedLocaleTags = (listOf("en") + appLocaleFolders).map { qualifier ->
    when (qualifier) {
        "zh-rCN" -> "zh-CN"
        "zh-rTW" -> "zh-TW"
        else -> qualifier.replace("-r", "-")
    }
}.sorted()
val generateLocaleCatalog = tasks.register<GenerateLocaleCatalogTask>("generateLocaleCatalog") {
    localeTags.set(generatedLocaleTags)
    outputDirectory.set(localeCatalogOutput)
}

// Never produce a Play bundle signed with a debug key when release properties are missing.
val verifyPlayReleaseSigning = tasks.register<VerifyPlayReleaseSigningTask>("verifyPlayReleaseSigning") {
    configured.set(hasReleaseSigning)
}
tasks.matching { it.name == "bundlePlayRelease" }.configureEach {
    dependsOn(verifyPlayReleaseSigning)
}

// Keep every release mapping: set MAPPING_ARCHIVE_DIR in local.properties to share one archive
// across worktrees; the default (android/release-mappings) is gitignored.
val mappingArchiveDir = props.getProperty("MAPPING_ARCHIVE_DIR")?.takeIf { it.isNotBlank() }
    ?.let { file(it) } ?: rootProject.file("release-mappings")
androidComponents {
    onVariants(selector().withBuildType("release")) { registerMappingArchive(it) }
    onVariants(selector().withBuildType("playRelease")) { registerMappingArchive(it) }
}
fun registerMappingArchive(variant: com.android.build.api.variant.ApplicationVariant) {
    val cap = variant.name.replaceFirstChar { it.uppercase() }
    val archive = tasks.register<ArchiveR8MappingTask>("archive${cap}R8Mapping") {
        // The artifact carries the dependency on R8 and the Compose mapping merge.
        mapping.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.OBFUSCATION_MAPPING_FILE))
        versionLabel.set("$appVersionName-${android.defaultConfig.versionCode}-${variant.name}")
        archiveDirectory.set(mappingArchiveDir)
        doNotTrackState("copies into an archive outside build/")
    }
    tasks.matching { it.name == "assemble$cap" || it.name == "bundle$cap" }.configureEach { finalizedBy(archive) }
}

tasks.register<Exec>("validateLocalizationResources") {
    group = "verification"
    description = "Validates complete translated display and speech resources."
    workingDir = rootProject.projectDir
    commandLine("python3", "scripts/validate_localization.py")
}
android.sourceSets.getByName("main").res.directories.add(localeCatalogOutput.get().asFile.absolutePath)
tasks.named("preBuild").configure { dependsOn(generateLocaleCatalog) }

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    generateProtoTasks {
        all().configureEach {
            builtins { create("java") { option("lite") } }
        }
    }
}

dependencies {
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation(project(":opus-codec"))
    implementation(libs.protobuf.java.lite)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.annotations)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.billing.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.aboutlibraries)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.backdrop)
//    implementation(libs.hilt)
//    implementation(libs.hilt.compiler)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation(libs.play.services.location)
    implementation(libs.osmdroid.android)
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.robolectric:robolectric:4.14")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(kotlin("test"))
}

aboutLibraries {
    export {
        prettyPrint = true
        excludeFields = listOf("generated")
        outputFile = file("src/main/res/raw/aboutlibraries.json")
    }
}
