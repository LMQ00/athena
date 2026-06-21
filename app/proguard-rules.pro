# libxposed XposedModule 保留（所有生命周期回调方法）
-keep class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    public void onSystemServerStarting(...);
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
}

# kotlinx.serialization：保留所有 @Serializable 类的序列化器
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.swipeguard.xposed.model.**$$serializer { *; }
-keepclassmembers class com.swipeguard.xposed.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.swipeguard.xposed.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# R8 资源压缩保留 R 类
-keep class **.R$* { *; }

# Compose 保留
-keepclasseswithmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
