plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace="fr.wokgui.phototv"
    compileSdk=35
    defaultConfig {
        applicationId="fr.wokgui.phototv"
        minSdk=23
        targetSdk=35
        versionCode=4
        versionName="0.4"
    }
    compileOptions {
        sourceCompatibility=JavaVersion.VERSION_17
        targetCompatibility=JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget="17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.coil-kt:coil:2.7.0")
}