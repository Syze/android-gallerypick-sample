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
# The page calls WebBridge.postMessage by name.
-keepclassmembers class co.getswan.gallerysample.WebBridge { @android.webkit.JavascriptInterface <methods>; }
# MediaPipe and OpenCV reach Java classes from native code by name; MediaPipe's options are protobufs read by reflection.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class org.opencv.** { *; }
# MediaPipe logs through Flogger, which finds its backend class by name; renamed, every MediaPipe task fails to start.
-keep class com.google.common.flogger.** { *; }
# Readable crash lines in tester reports.
-keepattributes SourceFile,LineNumberTable
# Classes MediaPipe names but this app never calls (the LiteRT-LM image embedder, an annotation, the graph profiler).
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.auto.value.extension.memoized.Memoized
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
# OpenCV's Java classes come from its jar without the package's resources; the camera view (unused) names them.
-dontwarn org.opencv.R$styleable
