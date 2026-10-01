plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val releaseStorePath = System.getenv("PHOTO_TV_RELEASE_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("PHOTO_TV_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("PHOTO_TV_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("PHOTO_TV_RELEASE_KEY_PASSWORD")
val hasSecureReleaseSigner = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace="fr.wokgui.phototv"
    compileSdk=35
    defaultConfig {
        applicationId="fr.wokgui.phototv"
        minSdk=23
        targetSdk=35
        versionCode=13
        versionName="0.13"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

        create("secureRelease") {
            if (hasSecureReleaseSigner) {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSecureReleaseSigner) {
                signingConfig = signingConfigs.getByName("secureRelease")
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
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.codelibs:jcifs:2.1.28")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
