# app proguard rules (Phase 1a frozen path; edits require a plan amendment).
# Media3: ExoPlayer/Session reflectively load extractors, renderers and the
# media3 session backend — keep them (Media3 shrinker guidance).
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.extractor.** { *; }
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.datasource.** { *; }
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.database.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keepnames @dagger.hilt.android.qualifiers.ApplicationContext class *

# kotlinx.serialization
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class kotlinx.serialization.** { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <methods>; }
-keep,includedescriptorclasses class com.pianostream.**$$serializer { *; }
-keepclassmembers class com.pianostream.** { *** Companion; }
-keepclasseswithmembers class com.pianostream.** { kotlinx.serialization.KSerializer serializer(...); }

# QuickJS native bridge
-keep class io.github.dokar3.quickjs.** { *; }

# OkHttp/OkIO
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature, Exceptions

# Room
-keep class androidx.room.** { *; }
