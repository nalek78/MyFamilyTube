package es.myfamilytube.auto;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Android Auto can't download pictures from the internet itself: it asks this provider,
 * which downloads the picture (kept on the phone) and hands it over. For songs it is the
 * album cover from MusicBrainz (Cover Art Archive) when it knows the song, otherwise the YouTube thumbnail.
 */
public class CarArtProvider extends ContentProvider {
    private static final String PATH = "art";

    static Uri uriFor(Context context, String imageUrl) {
        if (TextUtils.isEmpty(imageUrl)) {
            return null;
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".art")
                .appendPath(PATH)
                .appendQueryParameter("u", imageUrl)
                .build();
    }

    /** A song: the album cover from MusicBrainz if it knows the song, otherwise the YouTube picture. */
    static Uri uriForSong(Context context, Video video) {
        if (video == null || TextUtils.isEmpty(video.videoId)) {
            return uriFor(context, video != null ? video.getCardImageUrl() : null);
        }
        String youTube = !TextUtils.isEmpty(video.getCardImageUrl()) ? video.getCardImageUrl()
                : "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg";
        Uri.Builder builder = new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".art")
                .appendPath(PATH)
                .appendQueryParameter("u", youTube)
                .appendQueryParameter("id", video.videoId)
                .appendQueryParameter("t", video.getTitle() != null ? video.getTitle() : "");
        if (video.getAuthor() != null) {
            builder.appendQueryParameter("a", video.getAuthor());
        }
        return builder.build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        String url = uri.getQueryParameter("u");
        Context context = getContext();
        if (TextUtils.isEmpty(url) || context == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        // A song: first the album cover from MusicBrainz (short wait: Android Auto is waiting for it)
        String videoId = uri.getQueryParameter("id");
        if (!TextUtils.isEmpty(videoId)) {
            MusicArt.Info info = MusicArt.lookup(context, videoId, uri.getQueryParameter("t"), uri.getQueryParameter("a"), 2500);
            if (info != null && info.artUrl != null) {
                try {
                    return ParcelFileDescriptor.open(download(context, info.artUrl), ParcelFileDescriptor.MODE_READ_ONLY);
                } catch (Exception e) {
                    // the YouTube picture then
                }
            }
        }
        try {
            return ParcelFileDescriptor.open(download(context, url), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    private static final int MAX_FILES = 600;

    /**
     * Downloads a picture to the phone (cacheDir/art), or gives the one already there. Its own
     * download, not Glide's: on the phone Glide itself is asking this provider, and waiting
     * for another Glide download from inside it blocked the pictures.
     */
    static File download(Context context, String url) throws Exception {
        File dir = new File(context.getCacheDir(), "art");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new FileNotFoundException("no cache");
        }
        File file = new File(dir, hash(url) + ".img");
        if (file.length() > 0) {
            return file;
        }
        File part = new File(dir, hash(url) + "." + Thread.currentThread().getId() + ".part");
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        try {
            if (connection.getResponseCode() != 200) {
                throw new FileNotFoundException("HTTP " + connection.getResponseCode());
            }
            try (java.io.InputStream in = connection.getInputStream();
                 java.io.OutputStream out = new java.io.FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
        if (!part.renameTo(file) && file.length() == 0) {
            part.delete();
            throw new FileNotFoundException("rename");
        }
        part.delete();
        trim(dir);
        return file;
    }

    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_FILES) {
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_FILES * 3 / 4; i++) {
            files[i].delete(); // the oldest
        }
    }

    private static String hash(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(text.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode()) + "_" + text.length();
        }
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "image/jpeg";
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
