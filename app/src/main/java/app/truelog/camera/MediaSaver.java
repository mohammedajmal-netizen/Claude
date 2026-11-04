package app.truelog.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes into DCIM/TrueLog so the files appear in Gallery. */
final class MediaSaver {
    private static final String FOLDER = "DCIM/TrueLog";

    private MediaSaver() {}

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
    }

    private static Uri insert(Context c, boolean video, String name, String mime) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri base = video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = c.getContentResolver().insert(base, v);
        if (uri == null) throw new IOException("Could not create " + name);
        return uri;
    }

    private static void publish(Context c, Uri uri) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        c.getContentResolver().update(uri, v, null, null);
    }

    interface Writer {
        void write(OutputStream out) throws IOException;
    }

    static Uri saveImage(Context c, String name, String mime, Writer w) throws IOException {
        Uri uri = insert(c, false, name, mime);
        ContentResolver r = c.getContentResolver();
        try (OutputStream os = r.openOutputStream(uri)) {
            if (os == null) throw new IOException("Could not open " + name);
            w.write(os);
        } catch (IOException | RuntimeException e) {
            r.delete(uri, null, null);
            throw e;
        }
        publish(c, uri);
        return uri;
    }

    /** A video file that is still being written. */
    static final class PendingVideo {
        final Uri uri;
        final ParcelFileDescriptor fd;
        PendingVideo(Uri u, ParcelFileDescriptor f) {
            uri = u;
            fd = f;
        }
    }

    static PendingVideo beginVideo(Context c, String name) throws IOException {
        Uri uri = insert(c, true, name, "video/mp4");
        ParcelFileDescriptor fd = c.getContentResolver().openFileDescriptor(uri, "rw");
        if (fd == null) throw new IOException("Could not open " + name);
        return new PendingVideo(uri, fd);
    }

    static void finishVideo(Context c, PendingVideo p, boolean keep) {
        try {
            p.fd.close();
        } catch (IOException ignored) {
            // The muxer has already finished with this descriptor.
        }
        if (keep) publish(c, p.uri);
        else c.getContentResolver().delete(p.uri, null, null);
    }
}
