import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// バージョンは CI から環境変数で上書きできる（タグ v1.2.3 → versionName 1.2.3）。
val ciVersionName: String? = System.getenv("C2M_VERSION_NAME")?.takeIf { it.isNotBlank() }
val appVersionName = ciVersionName ?: "0.1.0"
val appVersionCode: Int = System.getenv("C2M_VERSION_CODE")?.toIntOrNull() ?: run {
    // "1.2.3" → 10203
    val parts = appVersionName.split('.', '-').mapNotNull { it.toIntOrNull() }
    (parts.getOrElse(0) { 0 } * 10000) + (parts.getOrElse(1) { 0 } * 100) + parts.getOrElse(2) { 0 }
}.coerceAtLeast(1)

// Release 署名情報は環境変数（GitHub Secrets 由来）または未コミットの keystore.properties からのみ取得する。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("C2M_RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = signingValue("C2M_RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("C2M_RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("C2M_RELEASE_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null } &&
    file(releaseStoreFile!!).exists()

android {
    namespace = "io.github.sekimotosa.c2mirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sekimotosa.c2mirror"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // リポジトリ同梱の「公開」Debug 鍵。環境ごとに ~/.android/debug.keystore が変わって
        // Debug APK の上書き更新ができなくなる問題を避けるためのもの。秘密ではなく、Release には絶対に使わない。
        getByName("debug") {
            storeFile = file("debug-shared.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Release 版と同居できるよう別 applicationId にする（署名鍵が異なるため）。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 署名情報が無い場合は未署名 APK になる。CI ではその場合リリースを中止する。
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
