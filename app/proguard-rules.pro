# Forbidden Streams TV — R8 rules (Nov 13 2026).
# Keep names so the crash report (Settings > Last crash report) stays readable.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Our own code: kept whole (JSON parsing, reflection-free but safest for an untested-on-device change).
-keep class com.forbiddenstreams.tv.** { *; }

# Media3 + the FFmpeg decoder (native code calls back into these classes).
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class org.jellyfin.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Optional pieces some libraries mention but don't need.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
