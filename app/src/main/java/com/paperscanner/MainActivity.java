package com.paperscanner;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int PICK = 1, CAM_PERM = 2;
    private WebView web;
    private ValueCallback<Uri[]> pending;
    private WebChromeClient.FileChooserParams pendingParams;
    private Uri photoUri;
    private PermissionRequest camRequest;
    private static final int LIVE_PERM = 3;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        s.setMediaPlaybackRequiresUserGesture(false);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT >= 23
                            && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        camRequest = req;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, LIVE_PERM);
                    } else {
                        req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                pendingParams = p;
                if (p.isCaptureEnabled() && Build.VERSION.SDK_INT >= 23
                        && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, CAM_PERM);
                } else {
                    openPicker();
                }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void openPicker() {
        Intent intent;
        photoUri = null;
        if (pendingParams.isCaptureEnabled()) {
            try {
                File f = File.createTempFile("scan_", ".jpg", getCacheDir());
                photoUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
                intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) {
                intent = pendingParams.createIntent();
            }
        } else {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(intent, PICK);
        } catch (Exception e) {
            pending.onReceiveValue(null);
            pending = null;
            Toast.makeText(this, "No camera or gallery app found", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == LIVE_PERM && camRequest != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
                camRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else {
                camRequest.deny();
                Toast.makeText(this, "Camera permission is needed to scan", Toast.LENGTH_LONG).show();
            }
            camRequest = null;
            return;
        }
        if (code == CAM_PERM && pending != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) openPicker();
            else {
                pending.onReceiveValue(null);
                pending = null;
                Toast.makeText(this, "Camera permission is needed to scan", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != PICK || pending == null) return;
        Uri[] out = null;
        if (result == RESULT_OK) {
            if (photoUri != null) out = new Uri[]{photoUri};
            else if (data != null && data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                out = new Uri[n];
                for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) out = new Uri[]{data.getData()};
        }
        pending.onReceiveValue(out);
        pending = null;
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    class Bridge {
        @JavascriptInterface
        public String saveFile(String name, String mime, String b64) {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            boolean image = mime.startsWith("image/");
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            image ? Environment.DIRECTORY_PICTURES + "/PaperScanner" : Environment.DIRECTORY_DOWNLOADS);
                    Uri col = image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    Uri uri = getContentResolver().insert(col, cv);
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(bytes); }
                } else {
                    File dir = getExternalFilesDir(image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOCUMENTS);
                    try (FileOutputStream os = new FileOutputStream(new File(dir, name))) { os.write(bytes); }
                }
                return "ok";
            } catch (Exception e) {
                return "Could not save " + name + ": " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void savePdf(String name, String b64) {
            final String r = saveFile(name, "application/pdf", b64);
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "ok".equals(r) ? "Saved to Downloads: " + name : r, Toast.LENGTH_LONG).show());
        }
    }
}
