package com.yallagoal.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * غلاف أندرويد رفيع حول tv.html. مسؤولياته الأساسية:
 *  1) WebView واحد بإعدادات صحيحة لبث الفيديو والاتصال بالإنترنت.
 *  2) جسر VLC/لوحة المفاتيح (WebAppInterface) — تشغيل خارجي بالكامل + بحث يعمل.
 *  3) ربط زر الرجوع الفعلي بمنطق handleBackAction()/isAtRootScreen() بالصفحة.
 *  4) إصلاح فقدان تركيز الريموت بعد العودة من تطبيق خارجي (VLC) — أهم إصلاح هنا.
 */
public class MainActivity extends Activity {

    private WebView webView;

    // يُنفَّذ داخل صفحة الويب عند كل ضغطة على زر الرجوع الفعلي بالجهاز/الريموت:
    // - إن كنا بشاشة جذرية (isAtRootScreen) لا معنى للرجوع عنها داخل الصفحة، نُعيد
    //   النص "root" فوراً دون تنفيذ أي شيء آخر، فيُغلق النشاط (Activity) من الطرف
    //   الأصلي (المستوى الأدنى) بدل أن يبقى المستخدم عالقاً بلا طريقة للخروج.
    // - غير ذلك، نُنفّذ handleBackAction() الأصلية بالصفحة (نفس المنطق المستخدم أصلاً
    //   لكل شاشة: الرجوع خطوة بمستويات Xtream، إغلاق نافذة خطأ، إلخ) ونُعيد "handled".
    private static final String BACK_HANDLER_JS =
            "(function(){"
                    + "  try {"
                    + "    if (typeof isAtRootScreen === 'function' && isAtRootScreen()) { return 'root'; }"
                    + "    if (typeof handleBackAction === 'function') { handleBackAction(); return 'handled'; }"
                    + "    return 'root';"
                    + "  } catch (e) { return 'root'; }"
                    + "})();";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        applyImmersiveMode();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // وضع تكبير الشاشة: نجعل الصفحة تملأ الشاشة بالكامل وتُحسب أبعادها كصفحة
        // سطح مكتب (overview) بدل تصغيرها كصفحة جوال افتراضياً — مهم جداً لصفحة
        // مصمَّمة أصلاً لملء شاشة تلفاز عريضة بواجهة عناصر تركيز كبيرة.
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // محتوى مختلط مسموح دائماً — بعض روابط سيرفرات Xtream قد تبقى http:// عادية،
        // ولم يعد هناك أي بروكسي وسيط يتكفّل بهذا لصفحة الويب نفسها بعد الآن.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // يمنع الصفحة من التنقّل خارج ملف tv.html المحلي نفسه (لو حاول أي رابط/تحويل
        // مفاجئ فتح عنوان خارجي) — منذ أضفنا جسر AndroidPlayer (يفتح VLC فعلياً)،
        // من المهم ألا تصل أي صفحة خارجية غير موثوقة لهذا الجسر مطلقاً.
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return !url.startsWith("file:///android_asset/");
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        webView.addJavascriptInterface(new WebAppInterface(this, webView), "AndroidPlayer");

        // ضروري حتى تصل ضغطات الريموت (الأسهم/OK) فعلياً لمستمع keydown داخل الصفحة
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        webView.loadUrl("file:///android_asset/tv.html");
        webView.requestFocus(View.FOCUS_DOWN);
    }

    private void applyImmersiveMode() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ─── إصلاح مشكلة اختفاء مؤشر تركيز الريموت بعد العودة من VLC ───────────────────
    // السبب الجذري: تشغيل فيديو أصبح يفتح نشاطاً (Activity) خارجياً منفصلاً بالكامل
    // (VLC) يأخذ تركيز النافذة (window focus) كلياً من نشاطنا. عند إغلاق VLC والعودة،
    // أندرويد لا "يُعيد" تركيز العرض (view focus) لعنصر الـWebView تلقائياً بمجرد
    // استعادة تركيز النافذة — يبقى الـWebView ظاهراً بصرياً لكنه لا يستقبل أي ضغطة
    // ريموت بعد الآن (فلا يظهر أي مؤشر تركيز مطلقاً). الإصلاح: نطلب تركيز الـWebView
    // صراحة (على مستوى أندرويد) في كل نقطة استئناف ممكنة، ونطلب من الصفحة نفسها إعادة
    // رسم مؤشر التركيز (tv-focus) صراحة أيضاً كطبقة حماية إضافية.
    private void reclaimWebViewFocus() {
        if (webView == null) return;
        webView.post(() -> {
            if (webView == null) return;
            webView.requestFocus(View.FOCUS_DOWN);
            webView.evaluateJavascript(
                    "if (typeof updateFocus === 'function') { updateFocus(); }", null);
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // بعض الأجهزة تُعيد إظهار أشرطة النظام عند استعادة التركيز (مثل الرجوع
            // من شاشة تطبيقات حديثة، أو من VLC) — نعيد فرض الوضع الغامر في كل مرة.
            applyImmersiveMode();
            reclaimWebViewFocus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
        applyImmersiveMode();
        reclaimWebViewFocus();
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView == null) {
                finish();
                return true;
            }
            webView.evaluateJavascript(BACK_HANDLER_JS, value -> {
                if (value != null && value.contains("root")) {
                    runOnUiThread(this::finish);
                }
            });
            // نستهلك الحدث دائماً هنا (نُعيد true) — القرار الفعلي (إغلاق التطبيق أم
            // لا) يُتَّخذ لاحقاً داخل رد نداء evaluateJavascript أعلاه، فلا نترك
            // النظام يتصرف بشكل افتراضي متزامن قد يتعارض مع هذا القرار.
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
