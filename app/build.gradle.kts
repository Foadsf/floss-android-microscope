import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

val pinnedNdkVersion = "26.1.10909125"

android {
    namespace = "com.floss.microscope"
    compileSdk = 35
    ndkVersion = pinnedNdkVersion

    defaultConfig {
        applicationId = "com.floss.microscope"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-FLOSS"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O3 -fopenmp -fexceptions"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Core & Coroutines
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Jetpack Compose & Material 3
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    // CameraX & Camera2 Interop
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // OpenCV Official Maven Package (FLOSS Apache 2.0)
    implementation("org.opencv:opencv:4.10.0")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.52")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // EXIF Interface
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}

// ---------------------------------------------------------------------------
// OpenMP runtime packaging.
// libmicroscope_cv.so is linked with -fopenmp, so it has a NEEDED entry for libomp.so.
// The NDK ships libomp.so but AGP does not package it, which would make
// System.loadLibrary("microscope_cv") fail at runtime. Copy the matching NDK
// build of libomp.so into a generated jniLibs folder for every ABI we ship.
// ---------------------------------------------------------------------------
fun resolveSdkDir(): File {
    System.getenv("ANDROID_HOME")?.let { return File(it) }
    System.getenv("ANDROID_SDK_ROOT")?.let { return File(it) }
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    return File(props.getProperty("sdk.dir") ?: error("Set ANDROID_HOME or sdk.dir in local.properties"))
}

val ompJniLibsDir = layout.buildDirectory.dir("generated/omp-jniLibs").get().asFile
val ompNdkArchByAbi = mapOf("arm64-v8a" to "aarch64", "armeabi-v7a" to "arm", "x86_64" to "x86_64")

val copyLibOmp by tasks.registering {
    val ndkDir = resolveSdkDir().resolve("ndk").resolve(pinnedNdkVersion)
    val outDir = ompJniLibsDir
    outputs.dir(outDir)
    doLast {
        val prebuilt = ndkDir.resolve("toolchains/llvm/prebuilt").listFiles()?.firstOrNull()
            ?: error("NDK $pinnedNdkVersion not found under $ndkDir")
        for ((abi, arch) in ompNdkArchByAbi) {
            val src = prebuilt.resolve("lib/clang").walkTopDown()
                .firstOrNull { it.isFile && it.name == "libomp.so" && it.parentFile.name == arch && it.parentFile.parentFile.name == "linux" }
                ?: error("libomp.so for $arch not found in NDK $pinnedNdkVersion")
            val dst = outDir.resolve(abi).resolve("libomp.so")
            dst.parentFile.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }
}

android.sourceSets.getByName("main").jniLibs.srcDir(ompJniLibsDir)

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(copyLibOmp)
}
