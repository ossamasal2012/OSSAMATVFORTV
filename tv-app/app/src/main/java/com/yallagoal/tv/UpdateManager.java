package com.yallagoal.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * يدير نظام تحديث التطبيق OTA بالكامل من طرف أندرويد:
 *  1) التحقق من وجود إصدار أحدث عبر version.json (يُنشَر تلقائياً على GitHub
 *     Releases مع كل إصدار — راجع .github/workflows/main.yml).
 *  2) تنزيل ملف الـAPK بالخلفية مع تقرير تقدّم حي (نسبة/حجم مُنزَّل/حجم إجمالي).
 *  3) فتح مثبّت أندرويد الرسمي تلقائياً فور اكتمال التنزيل عبر FileProvider.
 *
 * هذا الصف "أعمى" تماماً عن أي واجهة مستخدم عمداً — لا AlertDialog ولا
 * ProgressDialog (كلاهما قديم/مُهجَر Deprecated بأندرويد الحديث، ولا نستخدم أي
 * حل قديم إن وُجد بديل حديث). كل تحديث حالة هنا يُبلَّغ لصفحة tv.html عبر
 * evaluateJavascript لدوال جافاسكريبت محدَّدة سلفاً، فترسم هي نافذة التحديث
 * بنفس تصميم وألوان بقية التطبيق تماماً وبنفس نظام تنقّل الريموت الموحَّد.
 *
 * لماذا يجري فحص version.json من جافا وليس بـfetch() من جافاسكريبت؟ لأن
 * version.json وملف الـAPK يُنشَران كمرفقات ضمن GitHub Release (روابط
 * releases/latest/download/...)، وهذه الروابط لا تُرسِل ترويسة CORS
 * (Access-Control-Allow-Origin) المطلوبة كي ينجح fetch() من صفحة ويب — خلافاً
 * لـraw.githubusercontent.com المستخدم لبقية بيانات التطبيق. كود جافا هنا لا
 * يتأثر بهذا القيد إطلاقاً لأنه ليس متصفحاً.
 */
public class UpdateManager {

    // رابط ثابت دائماً يشير لآخر Release منشور بهذا المستودع تحديداً — GitHub نفسه
    // يُبقي "releases/latest/download/<اسم الملف>" مؤشّراً على أحدث إصدار تلقائياً،
    // فلا حاجة لمعرفة رقم/اسم الإصدار هنا مسبقاً مهما تعدَّدت الإصدارات لاحقاً.
    // *** إن غيّرت اسم مستودع GitHub أو مالكه مستقبلاً، حدِّث هذا الرابط فقط هنا. ***
    private static final String UPDATE_MANIFEST_URL =
            "https://github.com/ossamasal2012/OSSAMATVFORTV/releases/latest/download/version.json";

    private static final String APK_FILE_NAME = "yallagoal-tv-update.apk";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    // أقل فارق زمني (مللي ثانية) بين كل تحديث تقدّم يُرسَل لجافاسكريبت، لتفادي إغراق
    // جسر JS بمئات الاستدعاءات أثناء تنزيل سريع على شبكة قوية.
    private static final long PROGRESS_THROTTLE_MS = 150;

    private final Activity activity;
    private final WebView webView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isDownloading = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile File lastDownloadedApk = null;

    public UpdateManager(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    // ─────────────────────────── فحص وجود تحديث ───────────────────────────

    /**
     * يفحص version.json بالخلفية. لا يُغيّر أي شيء بالواجهة ولا يستدعي أي دالة
     * جافاسكريبت إطلاقاً إلا في حال تأكَّد فعلاً وجود إصدار أحدث من المثبَّت حالياً.
     * أي خلل (لا إنترنت، رابط غير متاح، JSON غير صالح...) يُتجاهَل بصمت تماماً —
     * فشل التحقق من التحديث لا يجب أبداً أن يمنع استخدام التطبيق بشكل طبيعي.
     */
    public void checkForUpdate() {
        executor.execute(() -> {
            try {
                String json = httpGetString(UPDATE_MANIFEST_URL);
                if (json == null) return;

                JSONObject remote = new JSONObject(json);
                long remoteVersionCode = remote.optLong("versionCode", -1);
                if (remoteVersionCode <= 0) return;

                PackageInfo pi = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0);
                long localVersionCode = getLongVersionCode(pi);
                String localVersionName = pi.versionName != null ? pi.versionName : String.valueOf(localVersionCode);

                if (remoteVersionCode <= localVersionCode) {
                    // النسخة الحالية محدَّثة بالفعل — إن تبقّى ملف تحديث قديم من دورة
                    // تحديث سابقة (نادراً: مثلاً تم تنزيله لكن لم يُثبَّت فوراً)، لم يعد
                    // له أي داعٍ الآن؛ نحذفه هنا تحديداً كي لا يتراكم بمساحة التخزين
                    // عبر إصدارات متعددة بلا داعٍ، ولضمان عدم وجود أي أثر لملفات تحديث
                    // قديمة بعد اكتمال كل عملية تحديث فعلياً.
                    cleanupStaleUpdateFile();
                    return;
                }

                JSONObject payload = new JSONObject();
                payload.put("versionCode", remoteVersionCode);
                payload.put("versionName", remote.optString("versionName", String.valueOf(remoteVersionCode)));
                payload.put("apkUrl", remote.optString("apk_url", ""));
                payload.put("forceUpdate", remote.optBoolean("force_update", false));
                payload.put("notes", remote.optString("release_notes", ""));
                payload.put("localVersionName", localVersionName);

                callJs("window.onUpdateAvailable(" + JSONObject.quote(payload.toString()) + ")");
            } catch (Exception e) {
                // نتجاهل أي خطأ هنا عمداً (راجع تعليق الدالة أعلاه).
            }
        });
    }

    // ─────────────────────────── تنزيل الـAPK ───────────────────────────

    /** يبدأ تنزيل الـAPK بالخلفية؛ يمنع أي تنزيل مزدوج متزامن تلقائياً. */
    public void startDownload(final String apkUrl) {
        if (apkUrl == null || apkUrl.trim().isEmpty()) {
            callJs("window.onUpdateDownloadFailed(" + JSONObject.quote("رابط التحديث غير صالح") + ")");
            return;
        }
        if (!isDownloading.compareAndSet(false, true)) {
            return; // يوجد تنزيل قيد التنفيذ بالفعل — تجاهل صامت (جافاسكريبت يمنع هذا أصلاً)
        }
        cancelRequested.set(false);

        executor.execute(() -> {
            File outFile = null;
            HttpURLConnection conn = null;
            boolean success = false;
            try {
                File dir = activity.getExternalFilesDir(null);
                if (dir == null) dir = activity.getFilesDir(); // احتياط نادر إن تعذّر التخزين الخارجي
                if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                    callJs("window.onUpdateDownloadFailed(" + JSONObject.quote("تعذّر تحضير مساحة التخزين") + ")");
                    return;
                }
                outFile = new File(dir, APK_FILE_NAME);
                if (outFile.exists()) //noinspection ResultOfMethodCallIgnored
                    outFile.delete(); // حذف أي بقايا تنزيل سابق قبل البدء من جديد

                URL url = new URL(apkUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*");
                conn.connect();

                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    callJs("window.onUpdateDownloadFailed(" + JSONObject.quote("فشل الاتصال بخادم التحديث (رمز " + code + ")") + ")");
                    return;
                }

                long total = getContentLength(conn);
                long downloaded = 0;
                long lastReportTime = 0;

                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (cancelRequested.get()) {
                            callJs("window.onUpdateDownloadFailed(" + JSONObject.quote("تم إلغاء التنزيل") + ")");
                            return;
                        }
                        out.write(buffer, 0, read);
                        downloaded += read;

                        long now = System.currentTimeMillis();
                        if (now - lastReportTime >= PROGRESS_THROTTLE_MS) {
                            lastReportTime = now;
                            final long d = downloaded, t = total;
                            callJs("window.onUpdateDownloadProgress(" + d + "," + t + ")");
                        }
                    }
                    out.flush();
                }

                if (cancelRequested.get()) {
                    callJs("window.onUpdateDownloadFailed(" + JSONObject.quote("تم إلغاء التنزيل") + ")");
                    return;
                }
                if (total > 0 && downloaded < total) {
                    callJs("window.onUpdateDownloadFailed(" + JSONObject.quote("انقطع الاتصال قبل اكتمال التنزيل") + ")");
                    return;
                }

                // إرسال تقدّم 100% أخير مضمون حتى لو انقضى العتبة الزمنية بالأعلى فوراً
                callJs("window.onUpdateDownloadProgress(" + downloaded + "," + (total > 0 ? total : downloaded) + ")");

                lastDownloadedApk = outFile;
                success = true;
                callJs("window.onUpdateDownloadComplete()");
                // "بعد اكتمال التنزيل يجب تشغيل مثبّت أندرويد تلقائياً" — نُنفّذ هذا مباشرة
                // هنا بلا انتظار أي طلب إضافي من الواجهة.
                mainHandler.post(this::installDownloadedApk);
            } catch (Exception e) {
                if (!cancelRequested.get()) {
                    callJs("window.onUpdateDownloadFailed(" + JSONObject.quote(
                            "تعذّر إكمال التنزيل. تحقّق من الإنترنت والمساحة المتوفرة.") + ")");
                }
            } finally {
                if (conn != null) conn.disconnect();
                if (!success && outFile != null && outFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                }
                isDownloading.set(false);
            }
        });
    }

    /** يُلغي تنزيلاً جارياً؛ يُحذَف أي ملف جزئي تلقائياً بمجرد توقّف حلقة القراءة. */
    public void cancelDownload() {
        cancelRequested.set(true);
    }

    // ─────────────────────────── تثبيت الـAPK ───────────────────────────

    /** يفتح مثبّت أندرويد الرسمي لآخر ملف APK تم تنزيله بنجاح عبر FileProvider (لا شاشة تثبيت مزيّفة). */
    public void installDownloadedApk() {
        activity.runOnUiThread(() -> {
            File apk = lastDownloadedApk;
            if (apk == null || !apk.exists()) {
                Toast.makeText(activity, "لا يوجد ملف تحديث جاهز للتثبيت بعد", Toast.LENGTH_SHORT).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity, "يرجى السماح بتثبيت التطبيقات من هذا المصدر ثم إعادة المحاولة", Toast.LENGTH_LONG).show();
                openInstallPermissionSettings();
                return;
            }
            try {
                Uri apkUri = FileProvider.getUriForFile(
                        activity, activity.getPackageName() + ".fileprovider", apk);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(activity, "تعذّر فتح مثبّت التطبيقات", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ─────────── صلاحية "تثبيت من مصادر غير معروفة" (Install unknown apps) ───────────

    public boolean canInstallUnknownApps() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true; // قبل أندرويد 8: صلاحية عامة واحدة بإعدادات النظام فقط، لا فحص برمجي مطلوب
        return activity.getPackageManager().canRequestPackageInstalls();
    }

    public void openInstallPermissionSettings() {
        activity.runOnUiThread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                }
            } catch (Exception ignored) {
                // بعض أجهزة/صناديق التلفاز المخصَّصة لا تدعم هذه الشاشة بعينها؛ تجاهل
                // الفشل بصمت هنا أفضل بكثير من تحطّم التطبيق بالكامل.
            }
        });
    }

    // ─────────────────────────── أدوات مساعدة ───────────────────────────

    private long getLongVersionCode(PackageInfo pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return pi.getLongVersionCode();
        }
        //noinspection deprecation
        return pi.versionCode;
    }

    /** getContentLengthLong() يتطلب API 24+؛ نستخدم البديل الأقدم (int) قبل ذلك — كافٍ تماماً لحجم APK عادي وminSdk هذا التطبيق هو 21. */
    private long getContentLength(HttpURLConnection conn) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return conn.getContentLengthLong();
        }
        return conn.getContentLength();
    }

    /** يحذف أي ملف تحديث APK متبقٍّ بمجلدي التخزين المحتمَلين (خارجي ثم داخلي احتياطياً) إن وُجد. آمن تماماً حتى لو لم يوجد أي ملف أصلاً. */
    private void cleanupStaleUpdateFile() {
        try {
            File extDir = activity.getExternalFilesDir(null);
            if (extDir != null) {
                File f = new File(extDir, APK_FILE_NAME);
                if (f.exists()) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
            File intFile = new File(activity.getFilesDir(), APK_FILE_NAME);
            if (intFile.exists()) //noinspection ResultOfMethodCallIgnored
                intFile.delete();
        } catch (Exception ignored) {
            // تنظيف اختياري بحت — أي فشل هنا لا يجب أن يؤثر على أي شيء آخر بالتطبيق.
        }
    }

    private void callJs(final String jsExpression) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || webView == null) return;
        webView.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            webView.evaluateJavascript(jsExpression, null);
        });
    }

    private String httpGetString(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json, */*");
            conn.connect();
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) return null;

            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bos.write(buffer, 0, read);
                }
                return bos.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** يُستدعى من WebAppInterface.shutdown() عند إغلاق النشاط لإيقاف أي عملية خلفية بأمان. */
    void shutdown() {
        cancelRequested.set(true);
        executor.shutdownNow();
    }
}
