import java.util.Properties

// Optional server settings built into the APK (never committed): app/keys.properties
//   CLOUD_URL=https://your-camera-worker.example
//   DEVICE_TOKEN=<value of the Worker secret DEVICE_TOKEN>
// Leave the file out entirely for a purely local install — everything works without it.
val keys = Properties().apply {
    val f = rootProject.file("app/keys.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun key(name: String) = (keys.getProperty(name) ?: "").trim().replace("\"", "")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.empco.petcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.empco.petcam"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "CLOUD_URL", "\"${key("CLOUD_URL")}\"")
        buildConfigField("String", "DEVICE_TOKEN", "\"${key("DEVICE_TOKEN")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.view)
    implementation(libs.camerax.effects)

    implementation(libs.okhttp)
    implementation(libs.work.runtime.ktx)
    implementation(libs.coroutines.android)
}
