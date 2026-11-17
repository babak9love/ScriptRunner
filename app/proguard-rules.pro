# Keep WebView related
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Squeeze a bit more out of R8
-repackageclasses ''
-allowaccessmodification
-dontwarn kotlin.**
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull(java.lang.Object);
    static void checkNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
}
