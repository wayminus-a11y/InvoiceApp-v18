package com.invoiceapp.manager;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets other apps (WhatsApp, Gmail ...) read ONE file from /InvoiceApp when the user presses SHARE.
 * Nothing else is exposed: only files inside the InvoiceApp folder, read-only, only with a temporary grant.
 */
public class FileShareProvider extends ContentProvider {
    public static final String AUTH = "com.invoiceapp.manager.fileshare";

    public static File root() {
        return new File(Environment.getExternalStorageDirectory(), "InvoiceApp");
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        try {
            String p = uri.getPath();
            if (p == null) throw new FileNotFoundException();
            File f = new File(Environment.getExternalStorageDirectory(), p);
            String base = root().getCanonicalPath() + File.separator;
            String c = f.getCanonicalPath();
            if (!c.startsWith(base) || !f.isFile()) throw new FileNotFoundException("not allowed");
            return f;
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("bad path");
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = resolve(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        String p = String.valueOf(uri.getPath()).toLowerCase();
        if (p.endsWith(".pdf")) return "application/pdf";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".xlsm")) return "application/vnd.ms-excel.sheet.macroEnabled.12";
        if (p.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
