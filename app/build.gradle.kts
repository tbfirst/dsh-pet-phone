import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 可选签名：把 keystore.properties（已 gitignore）放在仓库根目录即可自动启用 release 签名
// 注意：这里必须用文件顶部 import 的 Properties。写成 java.util.Properties 会与
// Gradle 的 `java {}` 扩展名冲突，Kotlin DSL 会报 Unresolved reference: util。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps: Properties? = if (keystorePropsFile.exists()) {
    Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
} else {
    null
}

android {
    namespace = "com.dshpet.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dshpet.phone"
        minSdk = 28          // AnimatedImageDrawable（动画 WebP）需要 Android 9+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // CI / 本地覆盖版本号：-PversionName=1.2.3 -PversionCode=12
        (project.findProperty("versionName") as String?)?.let { versionName = it }
        (project.findProperty("versionCode") as String?)?.let { versionCode = it.toInt() }
    }

    // 必须写在 buildTypes 之前：下面要用 getByName 取它
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 没有 keystore 时产出未签名 APK（仍可构建，供 CI 归档）
            signingConfig = if (keystoreProps != null) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/LICENSE*")
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
