package ca.paddock.demo;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coquille Android de la démo Paddock.
 * Affiche l'app web incluse dans l'APK, puis vérifie sur GitHub s'il existe une version plus récente
 * (version.json). Si oui, elle la télécharge et la propose au prochain redémarrage, sans réinstaller l'APK.
 */
public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 42;
    private static final String RAW = "https://raw.githubusercontent.com/morloftorder-maker/paddock-demo/main/";
    // Même adresse de base pour la version incluse et la version téléchargée : les comptes de la démo sont conservés.
    private static final String BASE = "file:///android_asset/index.html";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private SharedPreferences prefs;
    private volatile int loadedVersion = 0;
    private volatile int pendingVersion = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#121417"));
        w.setNavigationBarColor(Color.parseColor("#121417"));
        prefs = getSharedPreferences("paddock", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#121417"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // garde les comptes créés dans la démo
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "PaddockApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if ("file".equals(scheme) || "data".equals(scheme) || "about".equals(scheme)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (pendingVersion > loadedVersion) notifyReady();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Choisir une photo"), PICK_IMAGE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        loadBest();
    }

    /** Charge la version la plus récente disponible sur le téléphone : téléchargée ou incluse dans l'APK. */
    private void loadBest() {
        int bundled = bundledVersion();
        int cached = prefs.getInt("cachedVersion", 0);
        File f = cacheFile();
        if (cached > bundled && f.exists()) {
            try (InputStream in = new FileInputStream(f)) {
                String html = readAll(in);
                loadedVersion = cached;
                web.loadDataWithBaseURL(BASE, html, "text/html", "UTF-8", null);
                return;
            } catch (Exception ignored) { }
        }
        loadedVersion = bundled;
        web.loadUrl(BASE);
    }

    private int bundledVersion() {
        try (InputStream in = getAssets().open("version.json")) {
            return parseVersion(readAll(in));
        } catch (Exception e) {
            return 0;
        }
    }

    private File cacheFile() {
        return new File(getFilesDir(), "web-index.html");
    }

    private void checkForUpdate(final boolean manual) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String status;
                try {
                    long t = System.currentTimeMillis();
                    int remote = parseVersion(fetch(RAW + "version.json?t=" + t));
                    int have = Math.max(loadedVersion, prefs.getInt("cachedVersion", 0));
                    if (remote > have) {
                        String html = fetch(RAW + "web/index.html?t=" + t);
                        if (html.length() < 20000 || !html.contains("paddock-db-v3")) throw new IOException("fichier invalide");
                        try (FileOutputStream out = new FileOutputStream(cacheFile())) {
                            out.write(html.getBytes(StandardCharsets.UTF_8));
                        }
                        prefs.edit().putInt("cachedVersion", remote).apply();
                        pendingVersion = remote;
                        status = "ready";
                    } else if (prefs.getInt("cachedVersion", 0) > loadedVersion) {
                        pendingVersion = prefs.getInt("cachedVersion", 0);
                        status = "ready";
                    } else {
                        status = "none";
                    }
                } catch (Exception e) {
                    status = "error";
                }
                final String st = status;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if ("ready".equals(st)) notifyReady();
                        if (manual) web.evaluateJavascript("window.paddockUpdateResult&&window.paddockUpdateResult('" + st + "')", null);
                    }
                });
            }
        }).start();
    }

    private void notifyReady() {
        web.evaluateJavascript("window.paddockUpdateReady&&window.paddockUpdateReady(" + pendingVersion + ")", null);
    }

    private class Bridge {
        @JavascriptInterface
        public void checkUpdate() { checkForUpdate(true); }

        @JavascriptInterface
        public void applyUpdate() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pendingVersion = 0;
                    loadBest();
                }
            });
        }

        @JavascriptInterface
        public int version() { return loadedVersion; }
    }

    private static String fetch(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int parseVersion(String s) {
        Matcher m = Pattern.compile("\"web\"\\s*:\\s*(\\d+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_IMAGE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            checkForUpdate(false);
        }
    }
}
