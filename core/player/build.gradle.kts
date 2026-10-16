plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.solidplayer.core.player"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
}

dependencies {
    // 依赖方向单向向下：:core:player → :core:danmaku，反向禁止（文档 §3.2）
    api(project(":core:danmaku"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)
}
