# Room entities are accessed via generated code; keep for safety with JSON manifest reflection-free code.
-keepclassmembers class com.proofstamp.app.data.db.** { *; }
# ZXing
-dontwarn com.google.zxing.**
# Kotlin coroutines debug metadata
-dontwarn kotlinx.coroutines.**
