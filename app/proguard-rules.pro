# حماية بسيطة من الهندسة العكسية: تصغير وتعتيم أسماء الأصناف/المتغيرات الخاصة بنا
-keepattributes SourceFile
-renamesourcefileattribute SourceFile

# Shizuku يُستدعى جزئياً عبر الانعكاس (reflection) فلازم نحافظ على أسمائه
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

-dontwarn org.jetbrains.annotations.**
