plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 빌드 번호는 GitHub Actions 실행 번호를 사용 (앱 내 업데이트 비교용)
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.bkfamily.drivealert"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bkfamily.drivealert"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            // GitHub Secrets로 주입되는 고정 서명키 (카카오 키 해시와 일치해야 함)
            val ks = System.getenv("KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "drivealert"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation("com.kakaomobility.knsdk:knsdk_ui:1.12.8-hotfix02")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")
}
