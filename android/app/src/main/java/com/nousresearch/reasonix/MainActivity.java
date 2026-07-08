package com.nousresearch.reasonix;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private WebView webView;
    private Process serveProcess;
    private Handler mainHandler;
    private int servePort = -1;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() { extractAndLaunch(); }
        }).start();
    }

    private void extractAndLaunch() {
        try {
            String libDir = getApplicationInfo().nativeLibraryDir;
            File binary = new File(libDir, "libreasonix.so");
            File appCfg = new File(getFilesDir(), "reasonix.toml");

            // Try shared storage config first
            File sharedCfg = new File(
                Environment.getExternalStorageDirectory(),
                "termux_workspace/reasonix.toml");

            if (!sharedCfg.exists()) {
                // Check other common locations
                sharedCfg = new File("/sdcard/termux_workspace/reasonix.toml");
            }

            if (sharedCfg.exists()) {
                // Copy shared config to app dir
                copyFile(sharedCfg, appCfg);
            } else if (!appCfg.exists()) {
                // No config found — write a minimal one with note
                String cfg = "# No config found. Create reasonix.toml in\n"
                    + "# /sdcard/termux_workspace/ with your API key.\n"
                    + "# Example:\n"
                    + "# [provider.deepseek]\n"
                    + "# api_key = \"sk-...\"\n"
                    + "# api_base = \"https://api.deepseek.com/v1\"\n"
                    + "# [model]\n"
                    + "# default = \"deepseek-v4-flash\"\n"
                    + "# [sandbox]\n"
                    + "# bash = \"off\"\n";
                java.io.FileWriter fw = new java.io.FileWriter(appCfg);
                fw.write(cfg);
                fw.close();
            }

            // Start serve process
            ProcessBuilder pb = new ProcessBuilder(
                binary.getAbsolutePath(),
                "serve",
                "--addr", "127.0.0.1:0"
            );
            pb.directory(getFilesDir());
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.redirectErrorStream(true);
            serveProcess = pb.start();

            // Read port from output
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(serveProcess.getInputStream()));
            Pattern portPattern = Pattern.compile("127\\.0\\.0\\.1:(\\d+)");
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = portPattern.matcher(line);
                if (m.find()) {
                    servePort = Integer.parseInt(m.group(1));
                    break;
                }
            }

            if (servePort > 0) {
                final int port = servePort;
                mainHandler.post(new Runnable() {
                    public void run() { setupWebView(port); }
                });
            } else {
                mainHandler.post(new Runnable() {
                    public void run() {
                        showError("Could not find serve port.\n\n"
                            + "Put reasonix.toml in /sdcard/termux_workspace/");
                    }
                });
            }

        } catch (Exception e) {
            final String msg = e.getMessage();
            mainHandler.post(new Runnable() {
                public void run() { showError("Launch: " + msg); }
            });
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private void setupWebView(int port) {
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            public void onReceivedError(WebView view, int errorCode,
                String description, String failingUrl) {
                Toast.makeText(MainActivity.this,
                    "Error: " + description, Toast.LENGTH_LONG).show();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        FrameLayout layout = new FrameLayout(this);
        layout.addView(webView,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(layout);

        webView.loadUrl("http://127.0.0.1:" + port + "/");
    }

    private void showError(String message) {
        FrameLayout layout = new FrameLayout(this);
        TextView errorText = new TextView(this);
        errorText.setText(message);
        errorText.setTextSize(14);
        errorText.setPadding(48, 48, 48, 48);
        layout.addView(errorText,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(layout);
    }

    protected void onDestroy() {
        super.onDestroy();
        if (serveProcess != null) serveProcess.destroy();
    }
}
