plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace="fr.wokgui.phototv"
    compileSdk=35
    defaultConfig {
        applicationId="fr.wokgui.phototv"
        minSdk=23
        targetSdk=35
        versionCode=7
        versionName="0.7"
    }
    signingConfigs {
        create("stableDevelopment") {
            val stableStore = rootProject.file("build-keys/photo-tv-stable.p12")
            if (stableStore.exists()) {
                storeFile = stableStore
                storePassword = "phototv-stable"
                keyAlias = "phototv"
                keyPassword = "phototv-stable"
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            val stableStore = rootProject.file("build-keys/photo-tv-stable.p12")
            if (stableStore.exists()) {
                signingConfig = signingConfigs.getByName("stableDevelopment")
            }
        }
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