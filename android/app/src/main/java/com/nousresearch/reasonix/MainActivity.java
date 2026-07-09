package com.nousresearch.reasonix;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public class MainActivity extends Activity {

    private WebView webView;
    private Process serveProcess;
    private Handler mainHandler;
    private int servePort = -1;
    private SharedPreferences prefs;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("reasonix", MODE_PRIVATE);

        String apiKey = prefs.getString("api_key", "");
        if (apiKey.isEmpty()) {
            showConfigScreen();
        } else {
            startServe(apiKey);
        }
    }

    private void showConfigScreen() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 80, 48, 48);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Reasonix Setup");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, 16);
        layout.addView(title);

        TextView label = new TextView(this);
        label.setText("DeepSeek API Key:");
        label.setTextSize(14);
        label.setPadding(0, 16, 0, 8);
        layout.addView(label);

        EditText keyInput = new EditText(this);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT);
        keyInput.setHint("sk-...");
        keyInput.setTextSize(14);
        keyInput.setMinLines(2);
        layout.addView(keyInput);

        TextView label2 = new TextView(this);
        label2.setText("API Base (optional):");
        label2.setTextSize(14);
        label2.setPadding(0, 16, 0, 8);
        layout.addView(label2);

        EditText baseInput = new EditText(this);
        baseInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        baseInput.setText("https://api.deepseek.com/v1");
        baseInput.setTextSize(14);
        layout.addView(baseInput);

        Button saveBtn = new Button(this);
        saveBtn.setText("Save & Launch");
        saveBtn.setTextSize(16);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = 32;
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        saveBtn.setLayoutParams(bp);

        TextView statusText = new TextView(this);
        statusText.setTextSize(12);
        statusText.setPadding(0, 16, 0, 0);
        statusText.setGravity(Gravity.CENTER);

        saveBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String key = keyInput.getText().toString().trim();
                String base = baseInput.getText().toString().trim();
                if (key.isEmpty()) { statusText.setText("Please enter API key"); return; }
                prefs.edit().putString("api_key", key).putString("api_base", base).apply();
                statusText.setText("Saving...");
                startServe(key);
            }
        });

        layout.addView(saveBtn);
        layout.addView(statusText);
        setContentView(layout);
    }

    private String findBinaryPath() {
        StringBuilder diag = new StringBuilder();

        // Strategy 1: extract from APK's lib/ to app dir
        try {
            String apkPath = getApplicationInfo().sourceDir;
            diag.append("APK: ").append(apkPath).append("\n");
            ZipFile zf = new ZipFile(apkPath);
            java.util.Enumeration entries = zf.entries();
            while (entries.hasMoreElements()) {
                String name = ((java.util.zip.ZipEntry) entries.nextElement()).getName();
                if (name.endsWith("libreasonix.so")) {
                    diag.append("Found in APK: ").append(name).append("\n");
                    // Extract to app private dir
                    File dest = new File(getFilesDir(), "reasonix");
                    if (!dest.exists() || dest.length() == 0) {
                        InputStream in = zf.getInputStream(zf.getEntry(name));
                        FileOutputStream out = new FileOutputStream(dest);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        in.close();
                        out.close();
                        dest.setExecutable(true, false);
                    }
                    zf.close();
                    if (dest.exists() && dest.canExecute()) {
                        return dest.getAbsolutePath();
                    }
                    diag.append("Extracted but can't execute: ").append(dest.getAbsolutePath()).append("\n");
                }
            }
            zf.close();
        } catch (Exception e) {
            diag.append("Zip error: ").append(e.getMessage()).append("\n");
        }

        // Strategy 2: check nativeLibraryDir and sibling dirs
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        diag.append("NativeDir: ").append(nativeDir).append("\n");
        File f = new File(nativeDir, "libreasonix.so");
        if (f.exists()) return f.getAbsolutePath();

        File libDir = new File(nativeDir).getParentFile();
        if (libDir != null && libDir.isDirectory()) {
            File[] subs = libDir.listFiles();
            if (subs != null) {
                diag.append("Lib subdirs: ");
                for (File sub : subs) {
                    diag.append(sub.getName()).append(" ");
                    File c = new File(sub, "libreasonix.so");
                    if (c.exists()) return c.getAbsolutePath();
                }
                diag.append("\n");
            }
        }

        // Store diagnostic for error display
        try {
            FileWriter fw = new FileWriter(new File(getFilesDir(), "diag.txt"));
            fw.write(diag.toString());
            fw.close();
        } catch (Exception e) {}

        return null;
    }

    private void startServe(String apiKey) {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER);
        TextView loading = new TextView(this);
        loading.setText("Starting Reasonix...");
        loading.setTextSize(16);
        layout.addView(loading);
        setContentView(layout);

        new Thread(new Runnable() {
            public void run() {
                try {
                    String binaryPath = findBinaryPath();
                    if (binaryPath == null) {
                        // Read diagnostic
                        String diag = "";
                        try {
                            File f = new File(getFilesDir(), "diag.txt");
                            if (f.exists()) {
                                byte[] b = new byte[(int) f.length()];
                                java.io.FileInputStream fi = new java.io.FileInputStream(f);
                                fi.read(b);
                                fi.close();
                                diag = new String(b);
                            }
                        } catch (Exception e) {}
                        final String msg = diag.isEmpty() ? "Binary not found" : diag;
                        mainHandler.post(new Runnable() {
                            public void run() { showError(msg); }
                        });
                        return;
                    }

                    // Write config
                    File cfgFile = new File(getFilesDir(), "reasonix.toml");
                    String base = prefs.getString("api_base", "https://api.deepseek.com/v1");
                    String cfg = "[model]\ndefault = \"deepseek-v4-flash\"\n\n"
                        + "[provider.deepseek]\n"
                        + "api_key = \"" + apiKey + "\"\n"
                        + "api_base = \"" + base + "\"\n\n"
                        + "[sandbox]\nbash = \"off\"\n";
                    FileWriter fw = new FileWriter(cfgFile);
                    fw.write(cfg);
                    fw.close();

                    ProcessBuilder pb = new ProcessBuilder(
                        binaryPath, "serve", "--addr", "127.0.0.1:0");
                    pb.directory(getFilesDir());
                    pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                    pb.redirectErrorStream(true);
                    serveProcess = pb.start();

                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(serveProcess.getInputStream()));
                    Pattern pat = Pattern.compile("127\\.0\\.0\\.1:(\\d+)");
                    String line;
                    StringBuilder allOut = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        allOut.append(line).append("\n");
                        Matcher m = pat.matcher(line);
                        if (m.find()) { servePort = Integer.parseInt(m.group(1)); break; }
                        if (allOut.length() > 5000) break;
                    }

                    final int port = servePort;
                    mainHandler.post(new Runnable() {
                        public void run() {
                            if (port > 0) setupWebView(port);
                            else showError("Serve output:\n" + allOut.toString());
                        }
                    });
                } catch (Exception e) {
                    final String msg = e.getMessage();
                    mainHandler.post(new Runnable() {
                        public void run() { showError("Error: " + msg); }
                    });
                }
            }
        }).start();
    }

    private void setupWebView(int port) {
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);
        webView.loadUrl("http://127.0.0.1:" + port + "/");
    }

    private void showError(String msg) {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(48, 48, 48, 48);
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(14);
        layout.addView(tv);
        setContentView(layout);
    }

    protected void onDestroy() {
        super.onDestroy();
        if (serveProcess != null) serveProcess.destroy();
    }
}
