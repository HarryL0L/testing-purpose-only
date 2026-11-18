package io.github.harryl0l.a17;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts files from the phone into Android 17's Download folder, where its Files
 * app opens them (an APK there installs like on any phone). Files come shared
 * to "Android 17" from another app, opened with it, or picked here (the
 * notification's "Import file").
 */
public final class ImportActivity extends Activity {
    private static final int PICK = 1;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        List<Uri> uris = uris(getIntent());
        if (!uris.isEmpty()) {
            copy(uris);
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(pick, PICK);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == PICK && result == RESULT_OK && data != null) {
            copy(uris(data));
        } else {
            finish();
        }
    }

    private static List<Uri> uris(Intent i) {
        List<Uri> out = new ArrayList<>();
        if (i == null) return out;
        if (i.getData() != null) out.add(i.getData());
        ClipData clip = i.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) {
                Uri u = clip.getItemAt(k).getUri();
                if (u != null && !out.contains(u)) out.add(u);
            }
        }
        Object extra = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (extra instanceof Uri && !out.contains(extra)) out.add((Uri) extra);
        ArrayList<Uri> many = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (many != null) for (Uri u : many) if (!out.contains(u)) out.add(u);
        return out;
    }

    private void copy(List<Uri> uris) {
        File dir = new File(MainActivity.Paths.a17(this), "root/storage/emulated/0/Download");
        if (!MainActivity.Paths.installed(this)) {
            Toast.makeText(this, "Android 17 isn't installed yet", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Toast.makeText(this, "Copying to Android 17…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int ok = 0;
            String err = null;
            dir.mkdirs();
            dir.setReadable(true, false);
            dir.setExecutable(true, false);
            for (Uri u : uris) {
                String name = name(u);
                File dst = unique(dir, name);
                File tmp = new File(dir, "." + dst.getName() + ".part");
                try (InputStream in = getContentResolver().openInputStream(u);
                     OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                } catch (Exception e) {
                    tmp.delete();
                    err = name + ": " + e.getMessage();
                    continue;
                }
                tmp.setReadable(true, false);
                tmp.setWritable(true, false);
                if (tmp.renameTo(dst)) ok++;
            }
            String msg = ok > 0
                    ? (ok == 1 ? "1 file" : ok + " files")
                            + " in Android 17's Download folder: open Files there"
                    : "Nothing copied" + (err != null ? " (" + err + ")" : "");
            runOnUiThread(() -> {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                finish();
            });
        }, "a17-import-file").start();
    }

    private String name(Uri u) {
        String name = null;
        try (Cursor c = getContentResolver().query(u,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception e) {
            // a file:// or odd provider: the last path segment below
        }
        if (name == null) name = u.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "file";
        name = name.replace('/', '_');
        if (name.startsWith(".")) name = "_" + name;
        return name;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; f.exists(); n++) f = new File(dir, base + " (" + n + ")" + ext);
        return f;
    }
}
