package agent.foreground.test;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.SystemClock;
import android.os.Binder;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONArray;

/** Read-only test telemetry. Manifest requires the shell's DUMP permission. */
public final class StateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        if (Binder.getCallingUid() != 2000) throw new SecurityException("Shell-only test telemetry");
        if (!"/state".equals(uri.getPath())) throw new IllegalArgumentException("Unknown test endpoint");
        try {
            JSONObject state = new JSONObject(TestActivity.published);
            String after = uri.getQueryParameter("after");
            if (after != null && state.has("events")) {
                long sequence = Long.parseLong(after);
                JSONArray filtered = new JSONArray();
                JSONArray all = state.getJSONArray("events");
                for (int i = 0; i < all.length(); i++) {
                    JSONObject event = all.getJSONObject(i);
                    if (event.getLong("seq") > sequence) filtered.put(event);
                }
                state.put("events", filtered);
            }
            state.put("now", SystemClock.uptimeMillis());
            String encoded = Base64.encodeToString(state.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            MatrixCursor cursor = new MatrixCursor(new String[]{"payload"});
            cursor.addRow(new Object[]{encoded});
            return cursor;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.agent.foreground.state"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
