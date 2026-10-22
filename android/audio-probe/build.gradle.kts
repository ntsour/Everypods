plugins { alias(libs.plugins.android.application) }
android {
    namespace = "io.automated.ventures.everypods.audioprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.automated.ventures.everypods.audioprobe"
        minSdk = 33
        targetSdk = 37
        versionCode = 2
        versionName = "0.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
