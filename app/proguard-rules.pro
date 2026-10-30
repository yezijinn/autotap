# 保留无障碍服务子类：仅由系统按 Manifest 反射实例化，R8 看不到调用点
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# 崩溃栈保留源文件名与行号，便于线上定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 去掉发布包里的日志调用
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
