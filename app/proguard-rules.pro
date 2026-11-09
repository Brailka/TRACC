-keep class libv2ray.** { *; }
-keep class go.** { *; }
# JNI-классы: имена методов зашиты в нативных библиотеках.
-keep class org.amnezia.awg.GoBackend { native <methods>; }
-keep class app.tracc.net.RustNet { native <methods>; }

# В релизе вырезаются отладочные логи, чтобы адреса серверов и ключи не попадали в logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
