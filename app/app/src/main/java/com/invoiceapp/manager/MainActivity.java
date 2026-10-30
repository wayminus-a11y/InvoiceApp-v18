package com.invoiceapp.manager;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Offline wrapper (no internet permission). Shows assets/index.html in a locked-down WebView.
 * Files are saved in the phone's main storage:  InvoiceApp/PDF , InvoiceApp/Backup , InvoiceApp/Images
 * (if "all files access" is not given: Documents/InvoiceApp/... is used instead).
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_PERM = 2;
    private static final int REQ_AUTH = 3;
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private boolean jsLocked = false;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        applySecure(prefs.getBoolean("secure", true));
        getWindow().setStatusBarColor(Color.parseColor("#0F172A"));
        web = new WebView(this);
        setContentView(web);

        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // lock-down: the page is loaded from assets only, it can not read other files or the network
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setSavePassword(false);
        s.setSaveFormData(false);
        s.setGeolocationEnabled(false);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                return !u.startsWith("file:///android_asset/");   // never open anything else
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(Intent.createChooser(i, "Select backup file"), REQ_FILE);
                return true;
            }
        });

        if (Build.VERSION.SDK_INT < 30
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }
        web.loadUrl("file:///android_asset/index.html");
    }

    private void applySecure(boolean on) {
        if (on) getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override public void run() { if (web != null) web.evaluateJavascript(code, null); }
        });
    }

    @Override protected void onStop() { super.onStop(); js("window.__bg&&window.__bg()"); }
    @Override protected void onStart() { super.onStart(); js("window.__fg&&window.__fg()"); }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && filePathCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        } else if (requestCode == REQ_AUTH) {
            js("window.onDeviceAuth&&window.onDeviceAuth(" + (resultCode == RESULT_OK) + ")");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        js("window.onStorageChange&&window.onStorageChange()");
    }

    @Override
    public void onBackPressed() {
        if (jsLocked) { moveTaskToBack(true); return; }
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /* ---------------- storage helpers ---------------- */

    private boolean rootAllowed() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private String folderFor(String name, String mime) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf")) return "PDF";
        if (n.endsWith(".png") || n.endsWith(".jpg") || (mime != null && mime.startsWith("image/"))) return "Images";
        return "Backup";
    }

    private static String cleanName(String n) {
        return n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
    }

    /** returns the file written into the main-storage InvoiceApp folder, or null if not allowed */
    private File writeRoot(String name, String mime, byte[] data) throws Exception {
        if (!rootAllowed()) return null;
        File dir = new File(FileShareProvider.root(), folderFor(name, mime));
        if (!dir.exists() && !dir.mkdirs()) return null;
        File f = new File(dir, cleanName(name));
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(data);
        fos.close();
        MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
        return f;
    }

    /** MediaStore fallback: Documents/InvoiceApp/<folder> */
    private Uri writeMediaStore(String name, String mime, byte[] data) throws Exception {
        String folder = folderFor(name, mime);
        if (Build.VERSION.SDK_INT >= 29) {
            boolean isImg = "Images".equals(folder);
            String rel = (isImg ? "Pictures/InvoiceApp/" : "Documents/InvoiceApp/") + folder + "/";
            Uri coll = isImg ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Files.getContentUri("external");
            Uri uri = null;
            Cursor c = getContentResolver().query(coll, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{name, rel}, null);
            if (c != null) {
                if (c.moveToFirst()) uri = ContentUris.withAppendedId(coll, c.getLong(0));
                c.close();
            }
            if (uri == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, name.toLowerCase().endsWith(".xlsm") ? "application/octet-stream" : mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                uri = getContentResolver().insert(coll, v);
            }
            if (uri == null) throw new Exception("Cannot create file");
            OutputStream os = getContentResolver().openOutputStream(uri, "wt");
            if (os == null) throw new Exception("Cannot open file");
            os.write(data);
            os.close();
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "InvoiceApp/" + folder);
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Please allow storage access (Settings > Storage access)");
        File f = new File(dir, cleanName(name));
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(data);
        fos.close();
        return Uri.fromFile(f);
    }

    /** Functions the web page can call as  AndroidBridge.xxx(...) */
    private class Bridge {
        @JavascriptInterface
        public String saveFile(String name, String mime, String base64, String sub) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                File f = writeRoot(name, mime, data);
                if (f != null) return "InvoiceApp/" + folderFor(name, mime) + "/" + cleanName(name);
                writeMediaStore(name, mime, data);
                return (Build.VERSION.SDK_INT >= 29 && "Images".equals(folderFor(name, mime)) ? "Pictures" : "Documents")
                        + "/InvoiceApp/" + folderFor(name, mime) + "/" + name;
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String shareFile(final String name, final String mime, String base64, String sub) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                final Uri uri;
                File f = writeRoot(name, mime, data);
                if (f != null) {
                    String rel = "InvoiceApp/" + folderFor(name, mime) + "/" + cleanName(name);
                    uri = new Uri.Builder().scheme("content").authority(FileShareProvider.AUTH).encodedPath("/" + Uri.encode(rel, "/")).build();
                } else {
                    uri = writeMediaStore(name, mime, data);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType(mime);
                        i.putExtra(Intent.EXTRA_STREAM, uri);
                        i.setClipData(ClipData.newRawUri(name, uri));
                        i.putExtra(Intent.EXTRA_SUBJECT, name);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(i, "Share bill"));
                    }
                });
                return "OK";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void printPage() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                    PrintDocumentAdapter adapter = web.createPrintDocumentAdapter("Invoice");
                    pm.print("Invoice", adapter,
                            new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
                }
            });
        }

        /* ---- storage access (main-storage InvoiceApp folder) ---- */
        @JavascriptInterface
        public boolean storageOk() { return rootAllowed(); }

        @JavascriptInterface
        public void requestStorage() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            try { startActivity(i); }
                            catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                        } else {
                            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
                        }
                    } catch (Exception e) { /* ignore */ }
                }
            });
        }

        /* ---- security ---- */
        @JavascriptInterface
        public void setSecure(final boolean on) {
            prefs.edit().putBoolean("secure", on).apply();
            runOnUiThread(new Runnable() { @Override public void run() { applySecure(on); } });
        }

        @JavascriptInterface
        public boolean isSecure() { return prefs.getBoolean("secure", true); }

        @JavascriptInterface
        public void setLocked(boolean l) { jsLocked = l; }

        @JavascriptInterface
        public boolean deviceLockAvailable() {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isDeviceSecure();
        }

        @JavascriptInterface
        public void deviceAuth() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                        Intent i = km.createConfirmDeviceCredentialIntent("Invoice Manager", "Unlock with your phone lock");
                        if (i == null) { js("window.onDeviceAuth&&window.onDeviceAuth(false)"); return; }
                        startActivityForResult(i, REQ_AUTH);
                    } catch (Exception e) {
                        js("window.onDeviceAuth&&window.onDeviceAuth(false)");
                    }
                }
            });
        }

        @JavascriptInterface
        public int sdk() { return Build.VERSION.SDK_INT; }
    }
}
