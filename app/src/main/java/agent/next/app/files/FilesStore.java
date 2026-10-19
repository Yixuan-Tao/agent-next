package agent.next.app.files;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates a Download folder through MediaStore; no file-manager UI automation. */
public final class FilesStore {

    public record FolderSpec(String name) {
    }

    private static final Pattern NAME = Pattern.compile(
            "(?:名为|文件夹名[为是:：]?|文件夹\\s*[「\"']?)([\\p{L}\\p{N}_-]{1,40})");

    private final Context context;

    public FilesStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public static FolderSpec specFor(String goal, String intent) {
        String text = (goal == null ? "" : goal) + " " + (intent == null ? "" : intent);
        Matcher matcher = NAME.matcher(text);
        String name = matcher.find() ? matcher.group(1) : "agent-demo";
        if (name.endsWith("的文件夹")) {
            name = name.substring(0, name.length() - "的文件夹".length());
        } else if (name.endsWith("文件夹")) {
            name = name.substring(0, name.length() - "文件夹".length());
        }
        return new FolderSpec(name.isBlank() ? "agent-demo" : name);
    }

    public boolean exists(FolderSpec spec) {
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.Downloads._ID},
                MediaStore.Downloads.RELATIVE_PATH + "=?",
                new String[] {"Download/" + spec.name() + "/"},
                null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    public boolean insert(FolderSpec spec) {
        if (exists(spec)) {
            return true;
        }
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, "agent-folder.txt");
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/" + spec.name() + "/");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        try {
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return false;
            }
            try (java.io.OutputStream output = resolver.openOutputStream(uri)) {
                if (output != null) {
                    output.write(new byte[] {'\n'});
                }
            }
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, complete, null, null);
            return true;
        } catch (Exception failure) {
            return false;
        }
    }
}
