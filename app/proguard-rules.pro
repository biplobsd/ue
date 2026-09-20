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

# Coroutines
-dontwarn kotlinx.coroutines.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# WorkManager
-dontwarn androidx.work.**
