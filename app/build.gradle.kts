import java.io.File
import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "dev.javapad"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.javapad"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ECJ needs Android API stubs at runtime. Bundle the SDK's API 34 android.jar as a
// compressed asset, then copy it into app-private cache on first use.
val generatedAndroidJarAssets = layout.buildDirectory.dir("generated/androidJarAssets")
val generatedClasspathAssets = layout.buildDirectory.dir("generated/javaClasspathAssets")
android.sourceSets.getByName("main").assets.srcDir(generatedAndroidJarAssets)
android.sourceSets.getByName("main").assets.srcDir(generatedClasspathAssets)
val copyAndroidJarAsset by tasks.registering(Copy::class) {
    from(file("${android.sdkDirectory}/platforms/android-34/android.jar"))
    into(generatedAndroidJarAssets)
    rename { "android.jar" }
}
val debugRuntimeClasspath = configurations.named("debugRuntimeClasspath")
val copyJavaClasspathAssets by tasks.registering {
    inputs.files(debugRuntimeClasspath)
    outputs.dir(generatedClasspathAssets)
    doLast {
        val outputDir = generatedClasspathAssets.get().asFile.resolve("java-classpath").apply {
            parentFile.deleteRecursively()
            mkdirs()
        }
        debugRuntimeClasspath.get().files.forEachIndexed { index, artifact ->
            when (artifact.extension.lowercase()) {
                "jar" -> artifact.copyTo(File(outputDir, "$index-${artifact.name}"), overwrite = true)
                "aar" -> java.util.zip.ZipFile(artifact).use { archive ->
                    archive.entries().asSequence()
                        .filter { !it.isDirectory && (it.name == "classes.jar" || it.name.startsWith("libs/") && it.name.endsWith(".jar")) }
                        .forEachIndexed { innerIndex, entry ->
                            archive.getInputStream(entry).use { input ->
                                File(outputDir, "$index-$innerIndex-${File(entry.name).name}").outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                }
            }
        }
    }
}
tasks.named("preBuild").configure {
    dependsOn(copyAndroidJarAsset, copyJavaClasspathAssets)
}
tasks.configureEach {
    if (name == "mergeDebugAssets") {
        dependsOn(copyAndroidJarAsset, copyJavaClasspathAssets)
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Sora editor and its Java tree-sitter grammar. Versions and language setup follow
    // the official Sora Editor Getting Started guide and its Java demo app.
    implementation(platform("io.github.rosemoe:editor-bom:0.24.4"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-treesitter")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-java:4.3.2")
    implementation("com.itsaky.androidide.treesitter:android-tree-sitter:4.3.2")

    implementation("com.google.dagger:hilt-android:2.57.2")
    kapt("com.google.dagger:hilt-compiler:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // On-device Java compilation and bytecode conversion.
    implementation("org.eclipse.jdt:ecj:3.39.0")
    implementation("com.android.tools:r8:8.13.17")
    implementation("com.github.javaparser:javaparser-core:3.26.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
