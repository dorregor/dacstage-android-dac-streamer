plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

// Genera el Baseline Profile de :app sobre un equipo conectado (la TV real):
//   .\gradlew.bat :app:generateBaselineProfile -Pandroid.testInstrumentationRunnerArguments.class=... 
// Requiere instalar la app en el equipo: solo se ejecuta a mano (ver README, fase 5).
android {
    namespace = "io.github.dacstage.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro)
}
