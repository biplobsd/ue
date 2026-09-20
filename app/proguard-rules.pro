# Proguard / R8 configuration for Update Engine

# General rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Kotlinx Serialization
-dontnote kotlinx.serialization.**
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class dev.updateengine.hyperos.core.model.** {
    *;
}
-keep class dev.updateengine.hyperos.core.model.** { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# WorkManager & Room
-dontwarn androidx.work.**
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger {
    public <init>();
}
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
-dontwarn androidx.room.paging.**

# AndroidX Startup
-keep class * implements androidx.startup.Initializer {
    public <init>();
}

# AndroidX Lifecycle & ViewModel
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}

# UI Components (Miuix & Kyant0)
-dontwarn top.yukonga.miuix.**
-dontwarn io.github.kyant0.**
