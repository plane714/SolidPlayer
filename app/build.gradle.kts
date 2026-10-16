plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.solidplayer"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.solidplayer"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 12
        versionName = "0.5.1-p10"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 可选：只出单一 ABI 的包（`-PtargetAbi=arm64-v8a`）。
    //
    // 为什么不直接配死 `splits.abi`：默认产物必须是**通用包** ——
    // 单独发出去装到别人机器上时，谁也不知道对面是 arm64 还是 32 位。
    // 只有显式传参时才切分出单 ABI 包，行为与不传参时完全一致。
    //
    // 注意体积收益极小：本项目的 native 只有 `libandroidx.graphics.path.so` 与
    // `libdatastore_shared_counter.so` 两个小库（四个 ABI 加起来约 74 KB），
    // 播放内核走系统编解码器、没有自带 so。所以这个开关的意义是「按 ABI 分发」，
    // 不是「显著减小体积」。
    val targetAbi = providers.gradleProperty("targetAbi").orNull
    if (targetAbi != null) {
        splits {
            abi {
                isEnable = true
                reset()
                include(targetAbi)
                isUniversalApk = false
            }
        }
    }
}

dependencies {
    implementation(project(":core:player"))
    implementation(project(":core:danmaku"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // 播放
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
