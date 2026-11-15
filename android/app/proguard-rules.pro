# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# RTBuddy protobuf-lite schemas resolve generated instance fields by name.
# Keep generated messages intact, including primitive fields used by the schema.
-keep class io.automated.ventures.everypods.bluetooth.rtbuddy.proto.** extends com.google.protobuf.GeneratedMessageLite {
    *;
}

# WebRTC's native library (libjingle_peerconnection_so) looks up Java classes, methods and
# fields by name from JNI_OnLoad and at runtime; renaming or stripping them aborts the app
# ("JNI DETECTED ERROR ... java_class == null") as soon as a PeerConnectionFactory is created.
-keep class org.webrtc.** { *; }
-keep class org.webrtc.audio.** { *; }
-keep class org.jni_zero.** { *; }
-keep class J.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.webrtc.**
-dontwarn org.jni_zero.**
