import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val versionProps = Properties().apply {
    val file = rootProject.file("app/version.properties")
    if (file.exists()) load(file.inputStream())
}

fun versionProp(name: String, fallback: String): String =
    versionProps.getProperty(name) ?: fallback

android {
    namespace = "com.example.lctr_app"
    compileSdk = 35  // Здесь установлено API 35

    defaultConfig {
        val locatorApiUrl = (project.findProperty("locatorApiUrl") as String?)
            ?: "http://87.232.65.52:8080/api/location"
        val locatorApiBase = (project.findProperty("locatorApiBase") as String?)
            ?: locatorApiUrl.replace(Regex("/api/.*$"), "")
        val locatorPollIntervalMs = (project.findProperty("locatorPollIntervalMs") as String?)
            ?.toLongOrNull() ?: 15_000L

        applicationId = "com.example.lctr_app"
        minSdk = 28
        targetSdk = 35  // Здесь тоже указан API 35
        versionCode = versionProp("versionCode", "1").toInt()
        versionName = versionProp("versionName", "1.0.0")
        buildConfigField("String", "LOCATOR_API_URL", "\"$locatorApiUrl\"")
        buildConfigField("String", "LOCATOR_API_BASE", "\"$locatorApiBase\"")
        buildConfigField("long", "LOCATOR_POLL_INTERVAL_MS", "${locatorPollIntervalMs}L")
        buildConfigField("String", "DEFAULT_ADMIN_PIN", "\"2580\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.play.services.location)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation("com.google.android.material:material:1.9.0")

    // QR Code scanner
    implementation("com.google.zxing:core:3.4.1")
    implementation("com.journeyapps:zxing-android-embedded:4.2.0")

    // Retrofit для сетевых запросов
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
