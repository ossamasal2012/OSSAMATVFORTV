package com.yallagoal.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

/**
 * جسر جافاسكريبت ↔ أندرويد لهذا التطبيق. مسؤول عن أمرين فقط بالضبط بحسب الحاجة
 * الفعلية للصفحة:
 *  1) فتح أي رابط فيديو مباشرة بتطبيق VLC الخارجي (playInVlc) — هو التطبيق الوحيد
 *     المسؤول عن التشغيل الفعلي لكل محتوى الفيديو بهذا التطبيق الآن.
 *  2) إظهار/إخفاء لوحة المفاتيح البرمجية صراحة عند تركيز أي حقل نصي بالصفحة
 *     (رمز التفعيل + البحث) — عبر showKeyboard/hideKeyboard، تُستدعيان تلقائياً من
 *     صفحة الويب بحدثي focus/blur الحقيقيين لكل حقل (راجع tv.html). ضرورية لأن
 *     WebView لا يُظهر اللوحة بشكل موثوق دائماً عند تركيز حقل نصي عبر .focus() من
 *     جافاسكريبت (بخلاف لمسة مستخدم حقيقية) — خصوصاً على أجهزة التلفاز حيث التركيز
 *     يأتي أصلاً من ضغطة ريموت. للتعامل مع هذا بأقصى موثوقية ممكنة نجمع 3 طبقات
 *     معاً: (أ) restartInput لإجبار إعادة ربط قناة الإدخال بالحقل المُركَّز حالياً،
 *     (ب) WindowInsetsController الحديثة (أندرويد 11+) وهي الطريقة الموصى بها
 *     رسمياً وأكثر توافقاً مع الوضع الغامر (immersive) المستخدم بهذا التطبيق، مع
 *     (ج) InputMethodManager التقليدية كطبقة أمان لكل الإصدارات ولواجهات الشركات
 *     المصنِّعة التي لا تُطبّق (ب) كاملاً. ونُكرر المحاولة مرة إضافية بعد تأخير
 *     بسيط لتفادي تسابق توقيت معروف بين تركيز الحقل داخل الصفحة (غير متزامن داخلياً
 *     بمحرك Chromium) ولحظة وصول طلبنا.
 *
 * كل استدعاء @JavascriptInterface يصل على خيط WebView الداخلي (ليس بالضرورة خيط
 * الواجهة الرئيسي)، لذا كل عملية تؤثر على الواجهة هنا مُغلَّفة بـrunOnUiThread صراحة.
 */
public class WebAppInterface {

    private static final String VLC_PACKAGE = "org.videolan.vlc";

    // تأخير المحاولة الثانية لإظهار لوحة المفاتيح (مِلّي ثانية). قيمة صغيرة غير
    // محسوسة للمستخدم، لكنها كافية عملياً لتفادي تسابق التوقيت الموثَّق جيداً بين
    // لحظة تنفيذ .focus() على حقل الإدخال داخل صفحة الويب (تُعالَج داخلياً بمحرك
    // Chromium بشكل غير متزامن) ولحظة وصول طلبنا لإظهار اللوحة — فإن وصل طلبنا قبل
    // أن يُكمل WebView تجهيز قناة الإدخال الخاصة بالحقل المُركَّز حديثاً، يُتجاهَل
    // بصمت دون أي خطأ ظاهر.
    private static final long SHOW_KEYBOARD_RETRY_DELAY_MS = 200;

    private final Activity activity;
    private final WebView webView;
    // مرجع Runnable ثابت وحيد (وليس lambda جديدة بكل استدعاء) كي تنجح removeCallbacks
    // بإلغاء أي محاولة "إظهار" متأخرة معلّقة عند استدعاء hideKeyboard لاحقاً.
    private final Runnable showKeyboardRetry = this::forceShowKeyboardOnce;

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

    /**
     * يُظهر لوحة المفاتيح البرمجية صراحة. يُستدعى من صفحة الويب تلقائياً عند تركيز
     * أي حقل نصي فعلياً (حدث focus حقيقي — راجع تعليق tv.html). يحاول فوراً، ثم
     * يُكرر المحاولة بعد تأخير بسيط (انظر SHOW_KEYBOARD_RETRY_DELAY_MS) لتفادي
     * تسابق التوقيت المذكور أعلى الملف.
     */
    @JavascriptInterface
    public void showKeyboard() {
        activity.runOnUiThread(() -> {
            if (webView == null) return;
            webView.requestFocus();
            webView.removeCallbacks(showKeyboardRetry);
            forceShowKeyboardOnce();
            webView.postDelayed(showKeyboardRetry, SHOW_KEYBOARD_RETRY_DELAY_MS);
        });
    }

    /**
     * يُخفي لوحة المفاتيح البرمجية، ويُلغي أي محاولة "إظهار" متأخرة معلّقة (لو
     * انتقل المستخدم بسرعة بين حقلين خلال أقل من SHOW_KEYBOARD_RETRY_DELAY_MS، لا
     * نريد ظهور اللوحة للحظة عرضاً بعد طلب إخفائها مباشرة).
     */
    @JavascriptInterface
    public void hideKeyboard() {
        activity.runOnUiThread(() -> {
            if (webView == null) return;
            webView.removeCallbacks(showKeyboardRetry);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowInsetsController controller = webView.getWindowInsetsController();
                    if (controller != null) controller.hide(WindowInsets.Type.ime());
                }
                InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
                }
            } catch (Exception e) {
                // لا نُسقط التطبيق أبداً بسبب فشل إخفاء لوحة المفاتيح فقط.
            }
        });
    }

    /**
     * التنفيذ الفعلي لإظهار اللوحة، بكل الطرق المتاحة معاً كطبقات أمان متراكبة:
     *  1) restartInput: يُجبر أندرويد على التخلي عن أي "اتصال إدخال" قديم مخزَّن
     *     مسبقاً وإعادة سؤال WebView عن الحقل المُركَّز حالياً تحديداً — يمنع حالات
     *     ظهور لوحة لا تستقبل الكتابة فعلياً بالحقل الجديد (بقايا اتصال بحقل سابق).
     *  2) WindowInsetsController (أندرويد 11 / API 30 فأعلى): الطريقة الحديثة
     *     الموصى بها رسمياً من Google، وأكثر توافقاً مع الوضع الغامر (immersive)
     *     الذي يستخدمه هذا التطبيق أصلاً؛ الطريقة القديمة وحدها لم تعد مضمونة
     *     النتيجة دوماً على بعض واجهات الشركات المصنِّعة لصناديق التلفاز بدءاً من
     *     أندرويد 11.
     *  3) InputMethodManager.showSoftInput(SHOW_FORCED): تبقى مفعَّلة دوماً (حتى مع
     *     توفر الطريقة الحديثة) كطبقة أمان إضافية للأجهزة/الواجهات التي لا تُطبّق
     *     WindowInsetsController تطبيقاً كاملاً رغم توفره نظرياً بنظامها، ولكل
     *     الأجهزة الأقدم من أندرويد 11 (وهي كثيرة على صناديق التلفاز الرخيصة).
     */
    private void forceShowKeyboardOnce() {
        if (webView == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;

            imm.restartInput(webView);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = webView.getWindowInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.ime());
                }
            }

            imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED);
        } catch (Exception e) {
            // لا نُسقط التطبيق أبداً بسبب فشل إظهار لوحة المفاتيح فقط.
        }
    }
}
