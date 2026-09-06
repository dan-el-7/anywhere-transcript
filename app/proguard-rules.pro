# Keep JNI entry points
-keepclasseswithmembernames class com.anywhere.transcript.engine.WhisperEngine {
    native <methods>;
}
-keep class com.anywhere.transcript.engine.TranscriptionCallback { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
