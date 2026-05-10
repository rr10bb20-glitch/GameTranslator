# ── Game Translator — ProGuard Rules ──────────────────────

# احتفظ بـ JavascriptInterface (ClipboardBridge) من الحذف
-keepclassmembers class com.gametranslator.ClipboardBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# احتفظ بكل كلاسات التطبيق
-keep class com.gametranslator.** { *; }

# منع إزالة الـ Service والـ Activity
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity

# org.json مستخدم في FloatingTranslatorService
-keep class org.json.** { *; }

# تحذيرات عادية من مكتبات AndroidX — تجاهلها بأمان
-dontwarn androidx.**
