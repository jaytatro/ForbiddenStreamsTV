# Forbidden Vid — R8 rules. Names kept so crash reports stay readable.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

-keep class com.forbiddenvid.tv.** { *; }

# Media3 + the FFmpeg decoder (native code calls back into these classes).
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class org.jellyfin.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
