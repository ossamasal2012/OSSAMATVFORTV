package com.yallagoal.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

/**
 * جسر جافاسكريبت ↔ أندرويد لهذا التطبيق. مسؤول عن أمرين فقط بالضبط بحسب الحاجة
 * الفعلية للصفحة:
 *  1) فتح أي رابط فيديو مباشرة بتطبيق VLC الخارجي (playInVlc) — هو التطبيق الوحيد
 *     المسؤول عن التشغيل الفعلي لكل محتوى الفيديو بهذا التطبيق الآن.
 *  2) إظهار/إخفاء لوحة المفاتيح البرمجية صراحة عند استخدام حقل البحث
 *     (showKeyboard/hideKeyboard)، لأن WebView لا يُظهرها بشكل موثوق دائماً عند
 *     تركيز حقل نصي عبر استدعاء .focus() من جافاسكريبت (بخلاف لمسة مستخدم حقيقية).
 *
 * كل استدعاء @JavascriptInterface يصل على خيط WebView الداخلي (ليس بالضرورة خيط
 * الواجهة الرئيسي)، لذا كل عملية تؤثر على الواجهة هنا مُغلَّفة بـrunOnUiThread صراحة.
 */
public class WebAppInterface {

    private static final String VLC_PACKAGE = "org.videolan.vlc";

    private final Activity activity;
    private final WebView webView;

    public WebAppInterface(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    /**
     * يفتح رابط الفيديو مباشرة بتطبيق VLC (نفس تطبيق VLC العادي المتوفر بمتجر Play —
     * حزمة واحدة موحّدة تعمل بواجهة تتكيّف تلقائياً مع الهاتف/الجهاز اللوحي/التلفاز،
     * لا يوجد إصدار Android TV منفصل بحزمة مختلفة). لو لم يكن VLC مثبَّتاً، يُفتح
     * متجر Play مباشرة على صفحته لتثبيته بدل فشل صامت لا يوضّح السبب للمستخدم.
     */
    @JavascriptInterface
    public void playInVlc(final String url, final String title) {
        activity.runOnUiThread(() -> {
            if (url == null || url.trim().isEmpty()) return;

            if (!isPackageInstalled(VLC_PACKAGE)) {
                openVlcInStore();
                return;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setPackage(VLC_PACKAGE);
                intent.setDataAndTypeAndNormalize(Uri.parse(url), "video/*");
                intent.putExtra("title", title == null ? "" : title);
                intent.putExtra("android.intent.extra.title", title == null ? "" : title);
                intent.putExtra("from_start", true);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(activity, "تعذر فتح VLC لتشغيل هذا الرابط", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openVlcInStore() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + VLC_PACKAGE));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + VLC_PACKAGE));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Exception ignored) {
                Toast.makeText(activity, "يرجى تثبيت تطبيق VLC لتشغيل المحتوى", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            activity.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @JavascriptInterface
    public void showKeyboard() {
        activity.runOnUiThread(() -> {
            webView.requestFocus();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED);
            }
        });
    }

    @JavascriptInterface
    public void hideKeyboard() {
        activity.runOnUiThread(() -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
            }
        });
    }
}
