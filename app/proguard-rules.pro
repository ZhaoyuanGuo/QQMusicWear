# ---- 通用 ----
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions

# ---- kotlinx.serialization（@Serializable 模型反射生成序列化器） ----
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.qmusic.wear.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.qmusic.wear.**$$serializer { *; }
-keepclassmembers class com.qmusic.wear.** {
    *** Companion;
}
-keepclasseswithmembers class com.qmusic.wear.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- OkHttp / Okio（官方规则兜底，防平台反射裁剪） ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- 排除无用告警 ----
-dontwarn org.slf4j.**
