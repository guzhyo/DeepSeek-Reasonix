package com.nousresearch.reasonix;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

        // Extract binary and launch
        new Thread(new Runnable() {
            public void run() { extractAndLaunch(); }
        }).start();
    }

    private void extractAndLaunch() {
        try {
            String libDir = getApplicationInfo().nativeLibraryDir;
            File source = new File(libDir, "libreasonix.so");

            // Write config
            File cfgFile = new File(getFilesDir(), "reasonix.toml");
            String apiKey = "YOUR_DEEPSEEK_API_KEY";  // TODO: config screen
            String cfg = "[model]\ndefault = \"deepseek-v4-flash\"\n\n"
                + "[provider.deepseek]\napi_key = \"" + apiKey + "\"\n"
                + "api_base = \"https://api.deepseek.com/v1\"\n\n"
                + "[sandbox]\nbash = \"off\"\n";
            java.io.FileWriter fw = new java.io.FileWriter(cfgFile);
            fw.write(cfg);
            fw.close();

            // Start serve process
            ProcessBuilder pb = new ProcessBuilder(
                source.getAbsolutePath(),
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
                if (line.contains("serve")) {
                    // Try to find any port in the line
                    Matcher m2 = Pattern.compile(":(\\d{4,5})").matcher(line);
                    if (m2.find()) {
                        servePort = Integer.parseInt(m2.group(1));
                        break;
                    }
                }
            }

            if (servePort > 0) {
                final int port = servePort;
                mainHandler.post(new Runnable() {
                    public void run() { setupWebView(port); }
                });
            } else {
                mainHandler.post(new Runnable() {
                    public void run() { showError("Could not find serve port"); }
                });
            }

        } catch (Exception e) {
            final String msg = e.getMessage();
            mainHandler.post(new Runnable() {
                public void run() { showError("Launch failed: " + msg); }
            });
        }
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
                    "WebView error: " + description, Toast.LENGTH_LONG).show();
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        FrameLayout layout = new FrameLayout(this);
        layout.addView(webView,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(layout);

        String url = "http://127.0.0.1:" + port + "/";
        webView.loadUrl(url);
    }

    private void showError(String message) {
        FrameLayout layout = new FrameLayout(this);
        TextView errorText = new TextView(this);
        errorText.setText("Error: " + message);
        errorText.setTextSize(16);
        errorText.setPadding(48, 48, 48, 48);
        layout.addView(errorText,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(layout);
    }

    protected void onDestroy() {
        super.onDestroy();
        if (serveProcess != null) {
            serveProcess.destroy();
        }
    }
}
