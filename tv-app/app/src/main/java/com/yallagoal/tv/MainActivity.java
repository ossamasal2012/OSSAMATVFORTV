package com.yallagoal.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * غلاف أندرويد رفيع بالكامل حول tv.html — لا توجد أي واجهة جافاسكريبت مخصّصة
 * (JavascriptInterface) هنا لأن الصفحة لا تحتاج استدعاء كود أصلي إطلاقاً؛ كل ما
 * يلزم هو WebView واحد بإعدادات صحيحة، ووصل زر الرجوع الفعلي بالجهاز بمنطق
 * handleBackAction()/isAtRootScreen() المُعرَّف أصلاً داخل tv.html نفسها.
 */
public class MainActivity extends Activity {

    private WebView webView;

    // يُنفَّذ داخل صفحة الويب عند كل ضغطة على زر الرجوع الفعلي بالجهاز/الريموت:
    // - إن كنا بشاشة جذرية (isAtRootScreen) لا معنى للرجوع عنها داخل الصفحة، نُعيد
    //   النص "root" فوراً دون تنفيذ أي شيء آخر، فيُغلق النشاط (Activity) من الطرف
    //   الأصلي (المستوى الأدنى) بدل أن يبقى المستخدم عالقاً بلا طريقة للخروج.
    // - غير ذلك، نُنفّذ handleBackAction() الأصلية بالصفحة (نفس المنطق المستخدم أصلاً
    //   لكل شاشة: إغلاق المشغّل، الرجوع خطوة بمستويات Xtream، إلخ) ونُعيد "handled".
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
        // تشغيل الفيديو تلقائياً دون اشتراط "لمسة مستخدم" حقيقية من منظور WebView —
        // ضروري لأن التشغيل هنا يبدأ عبر ضغط ريموت (keydown)، وليس لمسة مباشرة على
        // عنصر الفيديو نفسه، وبدون هذا الإعداد يرفض WebView تشغيل الفيديو صامتاً.
        settings.setMediaPlaybackRequiresUserGesture(false);
        // محتوى مختلط مسموح دائماً (نفس نهج تطبيق الجوال بالضبط) — أغلب سيرفرات
        // Xtream تُستضاف عبر http:// عادي، وهذا دفاع إضافي بجانب بروكسي الكلاود
        // فلير الذي يمر عبره الطلب أصلاً.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // بعض الأجهزة تُعيد إظهار أشرطة النظام عند استعادة التركيز (مثل الرجوع من
        // شاشة تطبيقات حديثة) — نعيد فرض الوضع الغامر (immersive) في كل مرة.
        if (hasFocus) {
            applyImmersiveMode();
        }
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
