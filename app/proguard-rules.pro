# ONNX Runtime and its extensions are called from native code (JNI): keep their Java side intact.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { native <methods>; }
-dontwarn ai.onnxruntime.**

# Keep source file names and line numbers in stack traces (logcat), but rename everything else.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
