# Keep model classes for Firebase deserialization
-keep class com.callx.app.models.** { *; }
-keepclassmembers class com.callx.app.models.** { *; }
# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl
# Activities / services / app class
-keep class com.callx.app.CallxApp { *; }
-keep class com.callx.app.activities.** { *; }
-keep class com.callx.app.services.** { *; }
# JSON / Gson-style
-keepattributes Signature
-keepattributes *Annotation*
