# Keep OkHttp + WebView JS interface names
-keepattributes JavascriptInterface
-keep class cz.digitalnivedomi.diarium.webview.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**