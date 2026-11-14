# Keep Room entities & DAO
-keep class com.antigravity.duplicleaner.data.local.** { *; }

# Keep Domain models & Service classes
-keep class com.antigravity.duplicleaner.domain.model.** { *; }
-keep class com.antigravity.duplicleaner.service.** { *; }

# ProGuard / R8 Attributes
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }

# Coroutines and Media3 / Exif
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.media3.**
-dontwarn androidx.exifinterface.**
-keep class androidx.media3.** { *; }
-keep class androidx.exifinterface.** { *; }
-keep class androidx.core.content.FileProvider { *; }
