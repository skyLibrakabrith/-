# kotlinx.serialization 保留序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class at.cwiesner.android.visualtimer.ext.** {
    *** Companion;
}
-keepclasseswithmembers class at.cwiesner.android.visualtimer.ext.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class at.cwiesner.android.visualtimer.ext.**$$serializer { *; }
