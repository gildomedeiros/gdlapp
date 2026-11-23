package au.com.gildo.fm;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** The reusable simulator input in the public FM folder. */
final class SurferFixFile {
    private static final String NAME = "FM-surfer-fixes.csv";
    private static final String DIRECTORY = Environment.DIRECTORY_DOWNLOADS + "/FM/";
    static final String LOCATION = DIRECTORY + NAME;

    static List<GoTo.Point> load(Context context, SharedPreferences prefs) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri folder = SettingsFile.linkedFolder(prefs);
        InputStream input;
        if (folder == null && Build.VERSION.SDK_INT < 29) {
            input = new FileInputStream(legacy());
        } else {
            Uri file = folder == null ? findDownload(resolver) : SettingsFile.findLinkedFile(resolver, folder, NAME);
            if (file == null) throw new FileNotFoundException(LOCATION + " was not found. Save modes or link the FM folder.");
            input = resolver.openInputStream(file);
        }
        if (input == null) throw new IOException("Could not open " + LOCATION);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return SurferModes.decode(reader);
        }
    }

    static void save(Context context, SharedPreferences prefs, List<GoTo.Point> fixes) throws IOException {
        byte[] bytes = SurferModes.encode(fixes).getBytes(StandardCharsets.UTF_8);
        ContentResolver resolver = context.getContentResolver();
        Uri folder = SettingsFile.linkedFolder(prefs);
        if (folder != null) {
            Uri file = SettingsFile.findLinkedFile(resolver, folder, NAME);
            if (file != null) {
                write(resolver, file, bytes);
            } else {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder));
                Uri created = DocumentsContract.createDocument(resolver, parent, "text/csv", NAME);
                if (created == null) throw new IOException("Could not create " + LOCATION);
                try {
                    requireName(resolver, created, DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    write(resolver, created, bytes);
                } catch (IOException | RuntimeException failure) {
                    DocumentsContract.deleteDocument(resolver, created);
                    throw failure;
                }
            }
        } else if (Build.VERSION.SDK_INT < 29) {
            File file = legacy();
            File directory = file.getParentFile();
            Files.createDirectories(directory.toPath());
            File temp = File.createTempFile("FM-surfer-", ".tmp", directory);
            try {
                Files.write(temp.toPath(), bytes);
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp.toPath()); }
        } else {
            saveDownload(resolver, bytes);
        }
    }

    private static void saveDownload(ContentResolver resolver, byte[] bytes) throws IOException {
        Uri file = findDownload(resolver);
        if (file != null) {
            write(resolver, file, bytes);
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, NAME);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, DIRECTORY);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri created = resolver.insert(downloads(), values);
        if (created == null) throw new IOException("Could not create " + LOCATION);
        try {
            requireName(resolver, created, MediaStore.MediaColumns.DISPLAY_NAME);
            write(resolver, created, bytes);
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(created, values, null, null) != 1) throw new IOException("Could not publish " + LOCATION);
        } catch (IOException | RuntimeException failure) {
            resolver.delete(created, null, null);
            throw failure;
        }
    }

    private static Uri downloads() {
        return MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    private static Uri findDownload(ContentResolver resolver) {
        Uri collection = downloads();
        try (Cursor rows = resolver.query(collection, new String[]{MediaStore.MediaColumns._ID},
            MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
            new String[]{NAME, DIRECTORY}, null)) {
            return rows != null && rows.moveToFirst() ? ContentUris.withAppendedId(collection, rows.getLong(0)) : null;
        }
    }

    private static void requireName(ContentResolver resolver, Uri file, String column) throws IOException {
        try (Cursor row = resolver.query(file, new String[]{column}, null, null, null)) {
            if (row != null && row.moveToFirst() && NAME.equals(row.getString(0))) return;
        }
        throw new IOException(LOCATION + " already exists or is inaccessible. Use Link FM settings folder.");
    }

    private static void write(ContentResolver resolver, Uri file, byte[] bytes) throws IOException {
        try (OutputStream output = resolver.openOutputStream(file, "wt")) {
            if (output == null) throw new IOException("Could not write " + LOCATION);
            output.write(bytes);
        }
    }

    private static File legacy() {
        return new File(Environment.getExternalStorageDirectory(), LOCATION);
    }
}
