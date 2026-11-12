import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.github.dacstage"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.dacstage"
        // TV TCL: Android 11 (API 30), armeabi-v7a. Tablet: Android 16 (API 36), arm64-v8a.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // URL pública del proyecto para el User-Agent (MusicBrainz y LRCLIB piden poder contactar al autor).
        // Se define en gradle.properties: dacstage.projectUrl=https://github.com/<usuario>/<repo>
        val projectUrl = (project.findProperty("dacstage.projectUrl") as String?).orEmpty().trim()
        buildConfigField("String", "PROJECT_URL", "\"${projectUrl.replace("\"", "")}\"")
    }

    // Firma de release: keystore.properties en la raíz (fuera de git) con storeFile, storePassword,
    // keyAlias, keyPassword. Sin ese archivo, release se firma con la clave debug (solo uso personal).
    val keystoreProps = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreProps.exists()) {
            val p = Properties().apply { keystoreProps.inputStream().use(::load) }
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.media)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.adaptive)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)

    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Cliente ADB local (host, opcional): lee la cola de UAPP en Android/data via depuración inalámbrica.
    implementation(libs.libadb)
    implementation(libs.conscrypt)
    implementation(libs.bouncycastle.pkix)

    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
