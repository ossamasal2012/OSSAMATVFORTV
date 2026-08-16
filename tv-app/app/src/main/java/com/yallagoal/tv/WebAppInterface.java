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
 * جسر جافاسكريبت ↔ أندرويد لهذا التطبيق. مسؤول عن:
 *  1) فتح أي رابط فيديو مباشرة بمشغّل خارجي (VLC أو Just Player — يختار المستخدم
 *     أيهما لكل نوع محتوى من شاشة الإعدادات) — هما التطبيقان الوحيدان المسؤولان
 *     عن التشغيل الفعلي لكل محتوى الفيديو بهذا التطبيق الآن.
 *  2) إظهار/إخفاء لوحة المفاتيح البرمجية صراحة عند استخدام حقل البحث
 *     (showKeyboard/hideKeyboard)، لأن WebView لا يُظهرها بشكل موثوق دائماً عند
 *     تركيز حقل نصي عبر استدعاء .focus() من جافاسكريبت (بخلاف لمسة مستخدم حقيقية).
 *  3) تفويض كل شيء متعلق بتحديث التطبيق OTA لصف UpdateManager المخصَّص لذلك
 *     (فحص الإصدار، تنزيل الـAPK بتقدّم حي، فتح المثبّت، صلاحية المصادر غير المعروفة).
 *
 * كل استدعاء @JavascriptInterface يصل على خيط WebView الداخلي (ليس بالضرورة خيط
 * الواجهة الرئيسي)، لذا كل عملية تؤثر على الواجهة هنا مُغلَّفة بـrunOnUiThread صراحة.
 */
public class WebAppInterface {

    private static final String VLC_PACKAGE = "org.videolan.vlc";
    // Just (Video) Player — moneytoo/Player (com.brouken.player)، مفتوح المصدر، مسجَّل
    // رسمياً لمعالجة ACTION_VIEW لروابط http/https بنوع video/* (تحقَّقنا من هذا مباشرة
    // من AndroidManifest.xml الفعلي للمشروع قبل اعتماده، وليس تخميناً).
    private static final String JUST_PLAYER_PACKAGE = "com.brouken.player";

    private final Activity activity;
    private final WebView webView;
    private final UpdateManager updateManager;

    public WebAppInterface(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.updateManager = new UpdateManager(activity, webView);
    }

    /**
     * نقطة الدخول الموحَّدة الجديدة: يفتح رابط الفيديو بالمشغّل الخارجي الذي اختاره
     * المستخدم لنوع هذا المحتوى تحديداً من شاشة الإعدادات (playerId: "vlc" أو
     * "justplayer" — أي قيمة أخرى/غير معروفة تُعامَل كـVLC افتراضياً للأمان).
     */
    @JavascriptInterface
    public void playInExternalPlayer(final String url, final String title, final String playerId) {
        final String packageName = "justplayer".equals(playerId) ? JUST_PLAYER_PACKAGE : VLC_PACKAGE;
        activity.runOnUiThread(() -> launchExternalVideoPlayer(packageName, url, title));
    }

    /**
     * يفتح رابط الفيديو مباشرة بتطبيق VLC تحديداً. محفوظة بنفس الاسم والتوقيع الأصليين
     * للتوافق العكسي (كانت نقطة الدخول الوحيدة سابقاً)؛ تُفوِّض الآن لنفس المنطق
     * الموحَّد المستخدم مع أي مشغّل خارجي آخر.
     */
    @JavascriptInterface
    public void playInVlc(final String url, final String title) {
        activity.runOnUiThread(() -> launchExternalVideoPlayer(VLC_PACKAGE, url, title));
    }

    private void launchExternalVideoPlayer(String packageName, String url, String title) {
        if (url == null || url.trim().isEmpty()) return;

        if (!isPackageInstalled(packageName)) {
            openPlayerInStore(packageName);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setPackage(packageName);
            intent.setDataAndTypeAndNormalize(Uri.parse(url), "video/*");
            intent.putExtra("title", title == null ? "" : title);
            intent.putExtra("android.intent.extra.title", title == null ? "" : title);
            intent.putExtra("from_start", true);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "تعذر فتح مشغّل الفيديو لتشغيل هذا الرابط", Toast.LENGTH_SHORT).show();
        }
    }

    private void openPlayerInStore(String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + packageName));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Exception ignored) {
                Toast.makeText(activity, "يرجى تثبيت تطبيق تشغيل فيديو لتشغيل هذا المحتوى", Toast.LENGTH_LONG).show();
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

    // ─────────────────────── تحديث التطبيق OTA ───────────────────────
    // كل هذه الدوال تفويض رفيع فقط لصف UpdateManager — راجع تعليقاته للتفاصيل
    // الكاملة (لا يوجد أي منطق فعلي هنا عمداً، حفاظاً على وضوح فصل المسؤوليات).

    /** يتحقق من وجود إصدار أحدث بصمت؛ يستدعي window.onUpdateAvailable(...) بجافاسكريبت فقط إن وُجد فعلاً. */
    @JavascriptInterface
    public void checkForUpdate() {
        updateManager.checkForUpdate();
    }

    /** يبدأ تنزيل ملف الـAPK بالخلفية مع تقرير تقدّم حي لجافاسكريبت، ثم يفتح المثبّت تلقائياً عند الاكتمال. */
    @JavascriptInterface
    public void startUpdateDownload(final String apkUrl) {
        updateManager.startDownload(apkUrl);
    }

    /** يُلغي تنزيلاً جارياً (مثلاً عند ضغط المستخدم زر الرجوع أثناء التحميل) ويحذف أي ملف جزئي. */
    @JavascriptInterface
    public void cancelUpdateDownload() {
        updateManager.cancelDownload();
    }

    /** يفتح مثبّت أندرويد الرسمي لآخر ملف APK تم تنزيله بنجاح (يُستخدم تلقائياً وكزر "إعادة المحاولة" يدوي أيضاً). */
    @JavascriptInterface
    public void installDownloadedApk() {
        updateManager.installDownloadedApk();
    }

    /** true إن كان بإمكان التطبيق حالياً تشغيل مثبّت حزم أندرويد (صلاحية "تثبيت من مصادر غير معروفة"). */
    @JavascriptInterface
    public boolean canInstallUnknownApps() {
        return updateManager.canInstallUnknownApps();
    }

    /** يفتح شاشة إعدادات أندرويد لمنح صلاحية "تثبيت من مصادر غير معروفة" لهذا التطبيق تحديداً. */
    @JavascriptInterface
    public void openInstallPermissionSettings() {
        updateManager.openInstallPermissionSettings();
    }

    /** يُستدعى من MainActivity.onDestroy() لإيقاف أي عملية تنزيل/فحص خلفية بأمان. */
    void shutdown() {
        updateManager.shutdown();
    }
}
