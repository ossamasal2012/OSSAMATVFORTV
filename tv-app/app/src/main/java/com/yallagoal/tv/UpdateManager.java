package com.yallagoal.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Window;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpdateManager {
    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean isDownloading = false;
    private File pendingInstallFile;

    public UpdateManager(Activity activity) {
        this.activity = activity;
    }

    public void checkForUpdates() {
        executor.execute(() -> {
            try {
                JSONObject json = fetchJson(BuildConfig.UPDATE_JSON_URL);
                PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                long localCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
                int remoteCode = json.optInt("versionCode", 0);
                if (remoteCode <= localCode) return;
                String localName = info.versionName == null ? String.valueOf(localCode) : info.versionName;
                activity.runOnUiThread(() -> showUpdateDialog(json, localName));
            } catch (Exception e) {
                // فشل التحقق لا يمنع استخدام التطبيق.
            }
        });
    }

    private JSONObject fetchJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(9000);
        c.setReadTimeout(9000);
        c.setUseCaches(false);
        try (BufferedInputStream in = new BufferedInputStream(c.getInputStream())) {
            byte[] data = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(data)) != -1) sb.append(new String(data, 0, n));
            return new JSONObject(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    private void showUpdateDialog(JSONObject json, String localName) {
        boolean force = json.optBoolean("force_update", false);
        String remoteName = json.optString("versionName", String.valueOf(json.optInt("versionCode")));
        String notes = json.optString("release_notes", "");
        String message = "الإصدار الحالي: " + localName + "\nالإصدار الجديد: " + remoteName +
                (notes.trim().isEmpty() ? "" : "\n\n" + notes);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("يتوفر إصدار جديد من تطبيق يلا گول")
                .setMessage(message)
                .setPositiveButton("تحديث الآن", (d, w) -> startDownload(json.optString("apk_url", "")))
                .create();
        if (!force) dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "لاحقاً", (d, w) -> d.dismiss());
        dialog.setCancelable(!force);
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) window.getDecorView().setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
        });
        if (!activity.isFinishing()) dialog.show();
    }

    private void startDownload(String apkUrl) {
        if (isDownloading) {
            Toast.makeText(activity, "تحميل التحديث جارٍ بالفعل", Toast.LENGTH_SHORT).show();
            return;
        }
        if (apkUrl == null || apkUrl.trim().isEmpty()) {
            Toast.makeText(activity, "رابط التحديث غير صالح", Toast.LENGTH_LONG).show();
            return;
        }
        isDownloading = true;
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("جاري تحميل التحديث...");
        progress.setMessage("بدء التحميل...");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(false);
        progress.setCancelable(false);
        progress.show();

        executor.execute(() -> {
            File outFile = new File(new File(activity.getCacheDir(), "updates"), "yallagoal-update.apk");
            try {
                File dir = outFile.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(20000);
                int total = c.getContentLength();
                activity.runOnUiThread(() -> progress.setMax(total > 0 ? total : 100));
                try (BufferedInputStream in = new BufferedInputStream(c.getInputStream()); FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    long downloaded = 0;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        long done = downloaded;
                        activity.runOnUiThread(() -> updateProgress(progress, done, total));
                    }
                } finally {
                    c.disconnect();
                }
                pendingInstallFile = outFile;
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    installApk(outFile);
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(activity, "فشل تحميل التحديث. تحقق من الإنترنت والمساحة.", Toast.LENGTH_LONG).show();
                });
            } finally {
                isDownloading = false;
            }
        });
    }

    private void updateProgress(ProgressDialog progress, long downloaded, int total) {
        if (total > 0) {
            progress.setProgress((int) Math.min(downloaded, total));
            int percent = (int) Math.min(100, downloaded * 100 / total);
            progress.setMessage(percent + "%\n" + formatBytes(downloaded) + " / " + formatBytes(total));
        } else {
            progress.setIndeterminate(true);
            progress.setMessage(formatBytes(downloaded) + " تم تنزيلها");
        }
    }

    private String formatBytes(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f);
    }

    public void retryPendingInstallIfAllowed() {
        if (pendingInstallFile != null && pendingInstallFile.exists()) installApk(pendingInstallFile);
    }

    private void installApk(File apkFile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
                Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settingsIntent);
                Toast.makeText(activity, "فعّل السماح بتثبيت التطبيقات ثم عد للتطبيق", Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "تعذر فتح مثبت النظام للتحديث", Toast.LENGTH_LONG).show();
        }
    }
}
