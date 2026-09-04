plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.gridfix.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.gridfix.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 47
        versionName = "0.9.19"
        // MapTiler API key from the CI secret; empty in builds without it (community-tile fallback)
        buildConfigField("String", "MAPTILER_KEY", "\"" + (System.getenv("MAPTILER_KEY") ?: "") + "\"")
    }

    // Play upload key: CI decodes the keystore secret to a file and exports
    // GRIDFIX_KS + GRIDFIX_KS_PASS. Without them, release stays unsigned and
    // only the debug APK is built.
    val ksPath = System.getenv("GRIDFIX_KS")
    val ksPass = System.getenv("GRIDFIX_KS_PASS")
    signingConfigs {
        if (ksPath != null && ksPass != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = "gridfix"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            // R8: shrink + obfuscate. The CI keeps mapping.txt with every build so a
            // Play crash report can be de-obfuscated, and also builds a signed release
            // APK so this exact output can be installed and smoke-tested before upload.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // NGA (National Geospatial-Intelligence Agency) MGRS library, MIT licensed
    implementation("mil.nga:mgrs:2.1.3")

    // Map engine: osmdroid (Apache 2.0) — offline-first raster maps, MBTiles, tile cache
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // QR generation for position hand-off (Apache 2.0)
    implementation("com.google.zxing:core:3.5.3")

    // Google Play Billing for the GridFix Pro subscription
    implementation("com.android.billingclient:billing-ktx:9.1.0")

    // Transitive androidx.fragment 1.1.0 is flagged outdated by Play; pin a current one
    implementation("androidx.fragment:fragment:1.8.5")

    // Field math (MGRS, UTM, zone exceptions, ray fixes, sun/moon) is plain JVM code
    // and is the part that must never silently drift. See app/src/test.
    testImplementation("junit:junit:4.13.2")
}
