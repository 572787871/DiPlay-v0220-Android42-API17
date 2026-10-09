import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask

plugins {
    alias(libs.plugins.android.application)
}

// Standalone authentication is an explicit, local-only build input.  Do not fall back to a
// repository directory: a release must never silently package stale or unintended identity data.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }
val androidKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val androidKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val androidKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val androidKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val androidKeystoreFile = androidKeystorePath?.let { file(it).canonicalFile }
val externalSigningValues = listOf(
    androidKeystorePath,
    androidKeystorePassword,
    androidKeyAlias,
    androidKeyPassword,
)
val hasExternalSigning = externalSigningValues.all { !it.isNullOrBlank() }
check(externalSigningValues.all { it.isNullOrBlank() } || hasExternalSigning) {
    "Android signing configuration is incomplete"
}

android {
    namespace = "com.shilapi.xcertplay"
    testBuildType = providers.gradleProperty("legacyInstrumentationBuildType").getOrElse("debug")
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay"
        // The 2017 CS55 head unit runs Android 4.2.2/API 17.  Newer-only
        // paths remain runtime guarded; this is the installation baseline.
        minSdk = 17
        targetSdk = 36
        multiDexEnabled = true
        multiDexKeepProguard = file("multidex-config.pro")
        testInstrumentationRunner = "com.shilapi.xcertplay.T3LegacyInstrumentation"
        versionCode = 40
        versionName = "0.2.20"

    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            storeFile = androidKeystoreFile ?: file("missing-release-keystore.jks")
            storePassword = androidKeystorePassword ?: ""
            keyAlias = androidKeyAlias ?: ""
            keyPassword = androidKeyPassword ?: ""
            enableV1Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".hudtest"
            versionNameSuffix = "-hud-test"
            if (hasExternalSigning) signingConfig = signingConfigs.getByName("release")
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = if (hasExternalSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
}

val authenticationProbeRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// AGP 9.3 shrinks test L8 separately, shadowing target-app methods on native multidex Android.
// Keep a complete compatibility library in the test APK only; production remains unchanged.
afterEvaluate {
    tasks.withType<L8DexDesugarLibTask>().configureEach {
        if (name.endsWith("AndroidTest")) {
            keepRulesConfigurations.add("-keep class j$.** { *; }")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation("androidx.multidex:multidex:2.0.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    authenticationProbeRuntime(libs.bouncycastle)
    authenticationProbeRuntime("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
}

// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val requireStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
val verifyStandaloneAuthentication by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Validate standalone key/certificate matching and challenge signatures before packaging."
    dependsOn(requireStandaloneAuthentication, ":shared:bundleLibRuntimeToJarDebug")
    classpath(authenticationProbeRuntime,
        project(":shared").layout.buildDirectory.file(
            "intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar"))
    mainClass.set("com.shilapi.xcertplay.mfi.LocalMfiProbe")
    localAuthenticationAssets?.let { args(it.resolve("offline-mfi").absolutePath) }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
val verifyStandaloneSigning by tasks.registering {
    group = "verification"
    description = "Require the external Android signing key for an authenticated standalone APK."
    notCompatibleWithConfigurationCache("Reads protected signing inputs supplied only for this invocation")
    doLast {
        check(hasExternalSigning) { "Standalone builds require complete external Android signing variables" }
        check(androidKeystoreFile?.isFile == true) { "Standalone Android signing keystore is missing" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneSigning) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, verifyStandaloneSigning, "assembleDebug")
}

tasks.register("assembleStandaloneRelease") {
    group = "build"
    description = "Build a signed standalone APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, verifyStandaloneSigning, "assembleRelease")
}
