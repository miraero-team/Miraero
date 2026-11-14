import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// local.properties 에서 API 키 읽기 (이 파일은 GitHub에 안 올라감)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.miraero.driveapp"
    useLibrary("android.car")   // 차 속도 (Pleos Vehicle SDK에 속도 API가 없음)
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.miraero.driveapp"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 공공데이터포털 키 → 코드에서 BuildConfig.DATA_GO_KR_KEY 로 사용
        buildConfigField("String", "DATA_GO_KR_KEY", "\"${localProps.getProperty("DATA_GO_KR_KEY", "")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}