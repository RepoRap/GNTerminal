# Keep the JavaScript bridge used by the NSE/MCX WebView fallback.
-keepclassmembers class com.gnterminal.app.net.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
