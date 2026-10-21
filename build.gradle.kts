// 顶层构建脚本：声明各插件版本（版本集中定义在 gradle/libs.versions.toml）
// 注意：AGP 9.0 已内置 Kotlin 支持，无需再声明 kotlin.android 插件
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
