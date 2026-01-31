import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.vitlon.aitranslator"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.vitlon.aitranslator"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 👇 ĐOẠN CODE ĐỌC KEY (ĐÃ SỬA LỖI) 👇
        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(FileInputStream(localPropertiesFile))
        }

        // Dùng hàm này để nạp key vào BuildConfig
        buildConfigField("String", "GROQ_KEY", properties.getProperty("GROQ_API_KEY", "\"\""))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.text.recognition.chinese)
    implementation(libs.text.recognition.japanese)
    implementation(libs.text.recognition.korean)
    implementation(libs.translate)
    implementation(libs.okhttp)
    implementation(libs.core)
}

// DÁN VÀO CUỐI FILE build.gradle.kts
configurations.all {
    resolutionStrategy {
        // Ép dùng phiên bản annotations của Jetbrains (bản 23.0.0 khớp với lỗi của bạn)
        force("org.jetbrains:annotations:23.0.0")

        // Loại bỏ hoàn toàn thằng cũ gây rối
        exclude(group = "com.intellij", module = "annotations")
    }
}