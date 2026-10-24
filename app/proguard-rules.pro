# 保留 org.json（系统自带，无需处理）
-keep class com.dshpet.phone.** { *; }
-keepclassmembers class com.dshpet.phone.Persona { *; }
-dontwarn org.jetbrains.annotations.**
