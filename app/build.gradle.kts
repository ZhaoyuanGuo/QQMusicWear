import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 正式签名（可选）：根目录 keystore.properties 存在时启用独立 release keystore，
// 否则回退 debug 签名（保证本地/CI 无密钥时仍可直接构建安装）。
// 文件格式：storeFile / storePassword / keyAlias / keyPassword（key=value，UTF-8）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.qmusic.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qmusic.wear"
        minSdk = 27 // 依赖硬下限为 26（watchface-complications-data），如需覆盖 Android 8.0 手表可降至 26
        targetSdk = 36
        versionCode = 42
        versionName = "3.1.0"
    }

    testOptions {
        // 纯 JVM 单测会触达 android.util.Log 等桩 API（如 SourceEngine 桥接测试）
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // 质量门禁：lint 发现问题即失败（error 级阻断；warning 不阻断，避免存量告警卡 CI）
        abortOnError = true
        // release 构建同样执行 lint 检查
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    signingConfigs {
        if (hasReleaseKeystore) {
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
            // 有正式 keystore 用之，否则回退 debug 签名以便 release 包可直接安装（个人项目）
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)

    // Ambient（AOD）生命周期观察：Wear OS 4+ AmbientLifecycleObserver
    implementation(libs.wear)

    // Wear OS Tiles（「正在播放」磁贴）
    implementation(libs.wear.tiles)

    // Ongoing Activity（Wear OS 4+ 播放常驻卡片）
    implementation(libs.wear.ongoing)

    // 表盘 Complication 数据源（「正在播放」）
    implementation(libs.wear.complications)

    // Compose 基础 + Activity
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)

    // Lifecycle / ViewModel（lifecycle-process 提供 ProcessLifecycleOwner：全局前后台/灭屏感知）
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // 播放：ExoPlayer + MediaSession
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    // 网络与序列化
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // JS 引擎（音乐源插件运行时；纯 Java 实现无 ABI 限制）
    implementation(libs.rhino)

    // Ed25519 签名校验（BouncyCastle 底层 API：Android 部分设备的 JCA 未注册 Ed25519 KeyFactory）
    implementation(libs.bouncycastle)

    // 图片加载（Coil3 必须显式引入网络组件，否则 http 封面无法加载）
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // 封面主色提取（卡片流/播放页自适应配色）
    implementation(libs.palette.ktx)

    // 二维码生成：部分音乐源（酷狗/网易云）只返回二维码文本，由宿主渲染成图
    implementation(libs.zxing)

    // 单元测试（纯 JVM：签名校验 / DTO 契约）
    testImplementation(libs.junit)
}
