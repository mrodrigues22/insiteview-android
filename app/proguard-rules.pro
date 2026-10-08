# R8 rules for the release (and staging) build. Most libraries ship their own consumer rules:
# - kotlinx.serialization 1.11 (META-INF/com.android.tools/r8/*.pro): companions, serializer(),
#   INSTANCE of serializable objects, $$serializer descriptors. Type-safe navigation routes
#   (`navigate(route)` looks the serializer up by class) rely on these.
# - SceneView / ARSceneView 4.53: `-keep class com.google.android.filament.** { *; }`,
#   `com.google.ar.core.**`, kotlin-math, io.github.sceneview.collision.
# - Filament / gltfio / filament-utils 1.72: the @UsedBy JNI members, KTX1Loader, HDRLoader.
# - ARCore, ML Kit barcode scanning, CameraX, OkHttp, coroutines, Install Referrer, DataStore.
# What follows is what they leave out.

# Readable stack traces from Play Console and Sentry (the mapping file de-obfuscates names).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization (belt and braces next to its bundled rules): keep the generated
# serializers of our own models, which the API client and the stores look up at runtime.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.getinsiteview.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.getinsiteview.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Filament's native code calls back into these classes by name (env->FindClass); SceneView's rule
# already keeps the package, repeated here so a SceneView update that drops it can't break JNI.
-keep class com.google.android.filament.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ARSceneView references optional APIs the app doesn't ship (collaborative sessions over Nearby,
# Android XR faces and hands). R8 fails on missing classes unless told they're expected.
-dontwarn com.google.android.gms.nearby.**
-dontwarn androidx.xr.arcore.**
