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

import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.TimeUnit;

/**
 * Android Auto can't download pictures from the internet itself: it asks this provider,
 * which downloads the video's thumbnail (cached by Glide) and hands it over.
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
        try {
            File file = Glide.with(context).asFile().load(url).submit().get(20, TimeUnit.SECONDS);
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            throw new FileNotFoundException(e.getMessage());
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
