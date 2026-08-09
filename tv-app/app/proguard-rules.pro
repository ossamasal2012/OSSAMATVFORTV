# قواعد ProGuard/R8 اختيارية — التصغير (minifyEnabled) معطّل حالياً بـ build.gradle،
# فهذا الملف غير مُستخدَم فعلياً بالوقت الراهن. أُبقيه جاهزاً لو فُعِّل التصغير لاحقاً.

# الحفاظ على أي واجهة جافاسكريبت (@JavascriptInterface) لو أُضيفت مستقبلاً، حتى لا
# يحذفها R8 عن طريق الخطأ فتتوقف عن العمل من داخل صفحة الويب.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
