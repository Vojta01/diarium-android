# R8 keep rules for the release build (isMinifyEnabled = true since alpha33).
#
# OkHttp, Okio, Coil and the AndroidX libraries ship their own consumer rules;
# these are the reflective bits R8 cannot see by itself:

# 1. Tink (androidx.security.crypto → EncryptedSharedPreferences): loads its
#    primitives and keyset managers reflectively, so R8 must not rename them.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# 2. Firebase Messaging (push tokens): component discovery enumerates classes
#    and services reflectively.
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# 3. WorkManager instantiates Worker subclasses by class name.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 4. Platform org.json — some pulled-in libraries reference it at compile time
#    even though it lives in android.jar.
-dontwarn org.json.**

# 5. Debug-only coroutines agent fields.
-dontwarn kotlinx.coroutines.debug.**

# Attributes service loaders read at runtime.
-keepattributes *Annotation*, InnerClasses

-dontwarn okhttp3.**
-dontwarn okio.**
