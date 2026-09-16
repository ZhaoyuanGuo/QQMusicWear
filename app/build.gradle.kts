plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.qmusic.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qmusic.wear"
        minSdk = 33
        targetSdk = 36
        versionCode = 30
        versionName = "1.9.2"
    }

    buildTypes {
        release {
            // 使用 debug 签名以便 release 包可直接安装（个人项目）
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Wear OS Compose（Material 3 Expressive）
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")

    // Wear OS Tiles（「正在播放」磁贴）
    implementation("androidx.wear.tiles:tiles:1.5.0")

    // Compose 基础 + Activity
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    // 播放：ExoPlayer + MediaSession
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")

    // 网络与序列化
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // JS 引擎（音乐源插件运行时；纯 Java 实现无 ABI 限制）
    implementation("org.mozilla:rhino:1.7.15")

    // Ed25519 签名校验（BouncyCastle 底层 API：Android 部分设备的 JCA 未注册 Ed25519 KeyFactory）
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    // 图片加载（Coil3 必须显式引入网络组件，否则 http 封面无法加载）
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    // 封面主色提取（卡片流/播放页自适应配色）
    implementation("androidx.palette:palette-ktx:1.0.0")

    // 单元测试（纯 JVM：签名校验 / DTO 契约）
    testImplementation("junit:junit:4.13.2")
}
