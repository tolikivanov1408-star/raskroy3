package com.raskroy.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.Locale;

/**
 * Раскрой: оболочка с WebView, внутри которой работает то же приложение, что и на компьютере.
 * Экран поворачивается в любую сторону, а масштаб интерфейса подстраивается под диагональ устройства.
 */
public class MainActivity extends Activity {
    private static final int REQ_SAVE = 4711;
    private static final String START_URL = "file:///android_asset/index.html";

    /** Диагональ, с которой интерфейс показывается в натуральную величину (как на телефоне). */
    private static final double BASE_DIAGONAL_IN = 7.0;
    /** Самое крупное увеличение интерфейса на больших планшетах. */
    private static final double MAX_SCALE = 1.5;

    private WebView web;
    private byte[] pendingData;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF17181B);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "RaskroyAndroid");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                applyScale();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception e) {
                    toast("Не удалось открыть ссылку");
                }
                return true;
            }
        });
        web.loadUrl(START_URL);
    }

    /** Диагональ экрана в дюймах по физическим размерам. */
    private double diagonalInches() {
        DisplayMetrics real = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(real);
        double xdpi = real.xdpi > 0 ? real.xdpi : real.densityDpi;
        double ydpi = real.ydpi > 0 ? real.ydpi : real.densityDpi;
        double w = real.widthPixels / xdpi;
        double h = real.heightPixels / ydpi;
        double d = Math.sqrt(w * w + h * h);
        if (d < 3.0 || d > 30.0) {
            // система не сообщила разумных данных: считаем по независимым от плотности пикселям
            double wd = real.widthPixels / (double) real.density;
            double hd = real.heightPixels / (double) real.density;
            d = Math.sqrt(wd * wd + hd * hd) / 160.0;
        }
        return d;
    }

    /**
     * Подгоняет масштаб страницы под устройство: чем больше диагональ, тем крупнее интерфейс
     * (до MAX_SCALE), и страница всегда занимает всю ширину окна, в любой ориентации.
     */
    private void applyScale() {
        if (web == null) {
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        double widthDp = dm.widthPixels / (double) dm.density;
        double factor = Math.max(1.0, Math.min(MAX_SCALE, diagonalInches() / BASE_DIAGONAL_IN));
        int cssWidth = (int) Math.round(widthDp / factor);
        if (cssWidth < 200) {
            cssWidth = 200;
        }
        double scale = widthDp / cssWidth;
        String js = String.format(Locale.US,
                "(function(){var m=document.querySelector('meta[name=viewport]');"
                        + "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}"
                        + "m.setAttribute('content','width=%d,initial-scale=%.4f,minimum-scale=%.4f,maximum-scale=%.4f,user-scalable=no,viewport-fit=cover');})();",
                cssWidth, scale, scale, scale);
        web.evaluateJavascript(js, null);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // после поворота размеры окна устанавливаются не сразу: пересчитываем дважды
        web.post(new Runnable() {
            @Override
            public void run() {
                applyScale();
            }
        });
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyScale();
            }
        }, 350);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE) {
            return;
        }
        byte[] bytes = pendingData;
        pendingData = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || bytes == null) {
            return;
        }
        try {
            OutputStream os = getContentResolver().openOutputStream(data.getData(), "wt");
            if (os == null) {
                throw new IllegalStateException("нет потока записи");
            }
            try {
                os.write(bytes);
                os.flush();
            } finally {
                os.close();
            }
            toast("Файл сохранён");
        } catch (Exception e) {
            toast("Не удалось сохранить файл");
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "raskroy" : n;
    }

    private static String mimeFor(String name, String given) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (given != null && !given.isEmpty()) return given;
        return "application/octet-stream";
    }

    /** Мост для страницы: сохранение файлов, копирование и выход. Вызывается из JavaScript. */
    private class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            final byte[] bytes;
            try {
                bytes = Base64.decode(base64, Base64.DEFAULT);
            } catch (Exception e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast("Не удалось сохранить файл");
                    }
                });
                return;
            }
            final String fileName = safeName(name);
            final String type = mimeFor(fileName, mime);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pendingData = bytes;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(type);
                    i.putExtra(Intent.EXTRA_TITLE, fileName);
                    try {
                        startActivityForResult(i, REQ_SAVE);
                    } catch (Exception e) {
                        pendingData = null;
                        toast("Не удалось открыть окно сохранения");
                    }
                }
            });
        }

        @JavascriptInterface
        public void copyText(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Раскрой", text));
                    }
                }
            });
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }
    }
}
