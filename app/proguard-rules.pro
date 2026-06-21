-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

# ---- Xposed 模块入口保留规则 ----
# 保留所有 XposedModule 子类的构造器及 libxposed 框架回调方法
-keep,allowoptimization,allowobfuscation class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    public void onSystemServerStarting(...);
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
}

# ---- kotlinx.serialization 保留规则 ----
# 保留序列化器生成的伴生对象和内部类，避免反序列化时找不到 serializer
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.athena.xposed.model.**$$serializer { *; }
-keepclassmembers class com.athena.xposed.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.athena.xposed.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- 资源压缩保护 ----
# R 内部类可能被反射引用（Xposed 框架等不感知 R8）
-keep class **.R$* { *; }

# ---- Jetpack Compose 保留规则 ----
-keep,allowobfuscation class androidx.compose.** { *; }
-keepclasseswithmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
