// 顶层构建脚本：声明各插件版本
// 注意：AGP 9.0 已内置 Kotlin 支持，无需再声明 kotlin.android 插件
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
}
