package com.lettorepdf.app;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements PdfView.Listener {

    private static final int REQ_OPEN = 1;

    private PdfView pdfView;
    private TextView indicator;
    private View empty;
    private SharedPreferences prefs;
    private Uri currentUri;
    private boolean night;
    private final Runnable hideIndicator = () -> indicator.animate().alpha(0f).setDuration(300);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        pdfView = findViewById(R.id.pdf_view);
        indicator = findViewById(R.id.indicator);
        empty = findViewById(R.id.empty);
        prefs = getSharedPreferences("lettore", MODE_PRIVATE);

        night = prefs.getBoolean("night", false);
        pdfView.setNight(night);
        pdfView.setListener(this);
        findViewById(R.id.open_button).setOnClickListener(v -> pick());

        Intent it = getIntent();
        if (isViewIntent(it)) {
            openUri(it.getData(), false);
        } else {
            String last = prefs.getString("last_uri", null);
            if (last != null) openUri(Uri.parse(last), true);
        }
    }

    @Override protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        if (isViewIntent(it)) openUri(it.getData(), false);
    }

    private boolean isViewIntent(Intent it) {
        return it != null && Intent.ACTION_VIEW.equals(it.getAction()) && it.getData() != null;
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        startActivityForResult(i, REQ_OPEN);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            openUri(uri, false);
        }
    }

    private void openUri(Uri uri, boolean silent) {
        savePage();
        ParcelFileDescriptor fd = null;
        try {
            fd = getContentResolver().openFileDescriptor(uri, "r");
            if (fd == null) throw new java.io.IOException("null");
            pdfView.open(fd, prefs.getInt("page:" + uri, 0));
            currentUri = uri;
            empty.setVisibility(View.GONE);
            setTitle(displayName(uri));
            prefs.edit().putString("last_uri", uri.toString()).apply();
        } catch (Exception e) {
            if (fd != null) try { fd.close(); } catch (Exception ignored) { }
            if (silent) {
                prefs.edit().remove("last_uri").apply();
            } else {
                Toast.makeText(this, e instanceof SecurityException
                        ? R.string.err_protected : R.string.err_open, Toast.LENGTH_LONG).show();
            }
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n.replaceAll("(?i)\\.pdf$", "");
            }
        } catch (Exception ignored) { }
        String s = uri.getLastPathSegment();
        return s != null ? s : getString(R.string.app_name);
    }

    private void savePage() {
        if (currentUri != null && pdfView.getPageCount() > 0) {
            prefs.edit().putInt("page:" + currentUri, pdfView.getCurrentPage()).apply();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        savePage();
    }

    @Override protected void onDestroy() {
        pdfView.release();
        super.onDestroy();
    }

    // ---------- Menu ----------

    @Override public boolean onCreateOptionsMenu(Menu m) {
        getMenuInflater().inflate(R.menu.main, m);
        m.findItem(R.id.action_night).setChecked(night);
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu m) {
        boolean open = pdfView.getPageCount() > 0;
        m.findItem(R.id.action_goto).setVisible(open);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_open) { pick(); return true; }
        if (id == R.id.action_goto) { showGoTo(); return true; }
        if (id == R.id.action_night) {
            night = !night;
            item.setChecked(night);
            pdfView.setNight(night);
            prefs.edit().putBoolean("night", night).apply();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showGoTo() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 – " + pdfView.getPageCount());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(this)
                .setTitle(R.string.goto_page)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        pdfView.goToPage(Integer.parseInt(input.getText().toString().trim()) - 1);
                    } catch (NumberFormatException ignored) { }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------- Callback vista ----------

    @Override public void onPageChanged(int page, int count) {
        indicator.setText((page + 1) + " / " + count);
        indicator.animate().cancel();
        indicator.setAlpha(1f);
        indicator.removeCallbacks(hideIndicator);
        indicator.postDelayed(hideIndicator, 1500);
    }

    @Override public void onTap() {
        ActionBar ab = getActionBar();
        if (ab == null || pdfView.getPageCount() == 0) return;
        if (ab.isShowing()) ab.hide(); else ab.show();
    }
}
