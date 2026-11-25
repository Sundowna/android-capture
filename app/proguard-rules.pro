# ONNX Runtime and its extensions are called from native code (JNI): keep their Java side intact.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { native <methods>; }
-dontwarn ai.onnxruntime.**

# LiteRT-LM (the AI cleanup engine) also calls back into its Java side from native code: without
# this, release builds crash with "JNI DETECTED ERROR ... mid == null" when a conversation starts.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Keep source file names and line numbers in stack traces (logcat), but rename everything else.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
