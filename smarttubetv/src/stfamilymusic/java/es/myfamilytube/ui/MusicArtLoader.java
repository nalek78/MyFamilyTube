package es.myfamilytube.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import es.myfamilytube.auto.MusicArt;

/**
 * Covers on the phone, fast: the picture already known is shown at once (the MusicBrainz cover if it
 * was found before, otherwise the YouTube thumbnail), and MusicBrainz is asked in the background, a
 * few songs at a time. When a cover is found, the screen is told to draw again.
 */
final class MusicArtLoader {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> sAsking = Collections.synchronizedSet(new HashSet<>());
    private static Runnable sOnFound;

    private MusicArtLoader() {
    }

    /** Called (on the screen thread) each time a new MusicBrainz cover is found. */
    static void setOnFound(Runnable onFound) {
        sOnFound = onFound;
    }

    private static boolean isOurs(Uri uri) {
        return uri != null && "content".equals(uri.getScheme()) && uri.getAuthority() != null
                && uri.getAuthority().endsWith(".art");
    }

    /** What to load now: the MusicBrainz cover if known, the YouTube picture, or the uri itself. */
    static Object source(Context context, Uri uri) {
        if (!isOurs(uri)) {
            return uri;
        }
        String videoId = uri.getQueryParameter("id");
        if (videoId != null) {
            MusicArt.Info info = MusicArt.cached(context, videoId);
            if (info != null && info.artUrl != null) {
                return info.artUrl;
            }
        }
        String youTube = uri.getQueryParameter("u");
        return !TextUtils.isEmpty(youTube) ? youTube : uri;
    }

    static void into(ImageView view, Uri uri) {
        Context context = view.getContext();
        Object source = source(context, uri);
        if (source == null) {
            Glide.with(view).clear(view);
            view.setImageDrawable(null);
            return;
        }
        Glide.with(view).load(source).centerCrop().into(view);
        lookUp(context.getApplicationContext(), uri);
    }

    /** Asks MusicBrainz in the background, once per song. */
    static void lookUp(Context context, Uri uri) {
        if (!isOurs(uri)) {
            return;
        }
        String videoId = uri.getQueryParameter("id");
        if (videoId == null || MusicArt.known(context, videoId) || !sAsking.add(videoId)) {
            return;
        }
        String title = uri.getQueryParameter("t");
        String author = uri.getQueryParameter("a");
        POOL.execute(() -> {
            MusicArt.Info info = null;
            try {
                info = MusicArt.lookup(context, videoId, title, author);
            } finally {
                sAsking.remove(videoId);
            }
            if (info != null && info.artUrl != null) {
                MAIN.post(() -> {
                    Runnable onFound = sOnFound;
                    if (onFound != null) {
                        onFound.run();
                    }
                });
            }
        });
    }
}
