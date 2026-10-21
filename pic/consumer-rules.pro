# 库自身 API 不参与混淆
-keep class com.dawn.pic.** { *; }
# ONNX Runtime 通过 JNI 引用 Java 类，混淆后会找不到类
-keep class ai.onnxruntime.** { *; }
