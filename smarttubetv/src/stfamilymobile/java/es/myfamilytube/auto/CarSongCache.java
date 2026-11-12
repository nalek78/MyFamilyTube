package es.myfamilytube.auto;

import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small cushion of songs saved on the phone for the car, so a stretch without coverage
 * doesn't stop the music: the next songs of the list are downloaded while there is signal,
 * and only the last few played are kept (everything else is deleted).
 */
final class CarSongCache {
    private static final long MAX_SONG_BYTES = 40L * 1024 * 1024; // long mixes are not saved
    private static final int CHUNK = 1024 * 1024; // YouTube serves sound faster in pieces

    private final File mDir;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    /** Songs that should be on the phone right now (the others are deleted). */
    private volatile Set<String> mWanted = new HashSet<>();
    private final Set<String> mQueued = new HashSet<>();

    CarSongCache(Context context) {
        mDir = new File(context.getCacheDir(), "car_songs");
        //noinspection ResultOfMethodCallIgnored
        mDir.mkdirs();
    }

    /** The saved song, if it is complete. */
    File get(String videoId) {
        if (TextUtils.isEmpty(videoId)) {
            return null;
        }
        File[] files = mDir.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(videoId + ".") && !name.endsWith(".part") && file.length() > 0) {
                return file;
            }
        }
        return null;
    }

    /**
     * @param keep     songs to keep (the last ones played, the current one and the next ones)
     * @param download the next songs, in order, to save if they aren't yet
     */
    synchronized void update(Collection<String> keep, List<String> download) {
        Set<String> wanted = new HashSet<>(keep);
        wanted.addAll(download);
        mWanted = wanted;
        mExecutor.execute(this::deleteUnwanted);
        for (String videoId : download) {
            if (TextUtils.isEmpty(videoId) || get(videoId) != null || mQueued.contains(videoId)) {
                continue;
            }
            mQueued.add(videoId);
            mExecutor.execute(() -> {
                try {
                    if (mWanted.contains(videoId) && get(videoId) == null) {
                        download(videoId);
                    }
                } catch (Throwable e) {
                    // No coverage or YouTube refused it: it will play online if possible
                } finally {
                    synchronized (CarSongCache.this) {
                        mQueued.remove(videoId);
                    }
                }
            });
        }
    }

    void shutdown() {
        mExecutor.shutdownNow();
    }

    private void deleteUnwanted() {
        File[] files = mDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            int dot = name.indexOf('.');
            String videoId = dot > 0 ? name.substring(0, dot) : name;
            if (!mWanted.contains(videoId)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private void download(String videoId) throws Exception {
        MediaItemFormatInfo info = YouTubeServiceManager.instance().getMediaItemService().getFormatInfo(videoId);
        if (info == null || info.isUnplayable() || info.isLive()) {
            return;
        }
        MediaFormat audio = pickAudio(info.getAdaptiveFormats());
        if (audio == null || TextUtils.isEmpty(audio.getUrl())) {
            return;
        }
        long length = parseLong(audio.getClen());
        if (length <= 0 || length > MAX_SONG_BYTES) {
            return;
        }
        String extension = audio.getMimeType() != null && audio.getMimeType().contains("webm") ? "webm" : "m4a";
        File part = new File(mDir, videoId + "." + extension + ".part");
        File done = new File(mDir, videoId + "." + extension);
        try (OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            for (long start = 0; start < length; start += CHUNK) {
                if (!mWanted.contains(videoId)) {
                    throw new InterruptedException("no longer needed");
                }
                long end = Math.min(length, start + CHUNK) - 1;
                HttpURLConnection connection = (HttpURLConnection) new URL(audio.getUrl()).openConnection();
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(20_000);
                connection.setRequestProperty("User-Agent", DefaultHeaders.APP_USER_AGENT);
                connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
                try (InputStream in = connection.getInputStream()) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                } finally {
                    connection.disconnect();
                }
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        if (part.length() == length && mWanted.contains(videoId)) {
            //noinspection ResultOfMethodCallIgnored
            part.renameTo(done);
        } else {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    /** The original language (not a dub) in the best quality YouTube gives (usually Opus ~160 kbps). */
    private static MediaFormat pickAudio(List<MediaFormat> formats) {
        if (formats == null) {
            return null;
        }
        List<MediaFormat> audio = new ArrayList<>();
        boolean hasOriginal = false;
        for (MediaFormat format : formats) {
            if (format != null && format.getMimeType() != null && format.getMimeType().startsWith("audio/")
                    && !TextUtils.isEmpty(format.getUrl()) && !format.isDrc()) {
                audio.add(format);
                if (format.getLanguage() != null && format.getLanguage().contains("original")) {
                    hasOriginal = true;
                }
            }
        }
        MediaFormat best = null;
        for (MediaFormat format : audio) {
            if (hasOriginal && (format.getLanguage() == null || !format.getLanguage().contains("original"))) {
                continue;
            }
            if (best == null || score(format) > score(best)) {
                best = format;
            }
        }
        return best;
    }

    private static long score(MediaFormat format) {
        long bitrate = parseLong(format.getBitrate());
        boolean opus = format.getMimeType().contains("opus") || format.getMimeType().contains("webm");
        // Highest bitrate first; at the same bitrate Opus sounds better than AAC
        return bitrate * 10 + (opus ? 5 : 0);
    }

    private static long parseLong(String value) {
        try {
            return value != null ? Long.parseLong(value) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
