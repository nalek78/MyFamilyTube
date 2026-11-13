package es.myfamilytube.auto;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which of the profile's YouTube lists are music. It looks at the first videos of each list
 * (songs: «Artista - Canción», «Official Video», «- Topic» or VEVO channels, songs MusicBrainz knows…).
 * The answer is kept for a week. On the phone a list can also be hidden or shown by hand
 * (long press), and that always wins.
 */
final class MusicLists {
    private static final String PREFS = "music_lists";
    private static final long AUTO_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int SAMPLE = 15;

    private static final Pattern SONG_WORDS = Pattern.compile(
            "(official|oficial)\\s*(music\\s*)?(video|vídeo|audio|videoclip|clip)|videoclip|video\\s*oficial|audio\\s*oficial"
                    + "|lyrics?|\\bletra\\b|visuali[sz]er|\\bft\\.|\\bfeat\\.|\\bremix\\b|ac[uú]stic[oa]|\\(audio\\)|\\[audio\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MUSIC_TITLE = Pattern.compile(
            "(?<!\\p{L})(m[uú]sica|music|canciones|songs|hits|[ée]xitos|karaoke|rock|pop|reggaet[oó]n|rap|trap|flamenco|salsa|bachata"
                    + "|cumbia|jazz|blues|cl[aá]sica|techno|house|electr[oó]nica|dance|indie|metal|baladas|villancicos|bso|banda sonora|80s|90s)(?!\\p{L})",
            Pattern.CASE_INSENSITIVE);

    private MusicLists() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Hidden or shown by hand on the phone (null: never touched). */
    static Boolean manual(Context context, String profileId, String playlistId) {
        String value = prefs(context).getString("m:" + profileId + ":" + playlistId, null);
        return value == null ? null : "1".equals(value);
    }

    static void setManual(Context context, String profileId, String playlistId, boolean music) {
        prefs(context).edit().putString("m:" + profileId + ":" + playlistId, music ? "1" : "0").apply();
    }

    /** What was decided by looking at the list (null: not known yet, or more than a week old). */
    static Boolean automatic(Context context, String playlistId) {
        String value = prefs(context).getString("a:" + playlistId, null);
        if (value == null) {
            return null;
        }
        int bar = value.indexOf('|');
        try {
            long time = Long.parseLong(value.substring(bar + 1));
            if (System.currentTimeMillis() - time > AUTO_MS) {
                return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return value.startsWith("1");
    }

    static void saveAutomatic(Context context, String playlistId, boolean music) {
        prefs(context).edit().putString("a:" + playlistId, (music ? "1" : "0") + "|" + System.currentTimeMillis()).apply();
    }

    /** A list created from this app: it is music, whatever its name. */
    static void rememberCreated(Context context, String profileId, String name) {
        if (!TextUtils.isEmpty(name)) {
            prefs(context).edit().putBoolean("c:" + profileId + ":" + MusicArt.normalize(name), true).apply();
        }
    }

    static boolean wasCreated(Context context, String profileId, String name) {
        return !TextUtils.isEmpty(name) && prefs(context).getBoolean("c:" + profileId + ":" + MusicArt.normalize(name), false);
    }

    /** Its name already says it is music («Música para el coche», «Éxitos 2025»…). */
    static boolean titleSaysMusic(String title) {
        return title != null && MUSIC_TITLE.matcher(title).find();
    }

    /** Is this list music? null: it has to be looked at first. */
    static Boolean isMusic(Context context, String profileId, Video list) {
        Boolean byHand = manual(context, profileId, list.playlistId);
        if (byHand != null) {
            return byHand;
        }
        if (wasCreated(context, profileId, list.getTitle())) {
            return Boolean.TRUE;
        }
        Boolean auto = automatic(context, list.playlistId);
        if (auto != null) {
            return auto;
        }
        return titleSaysMusic(list.getTitle()) ? Boolean.TRUE : null;
    }

    /** At least 4 out of 10 of the first videos look like songs. */
    static boolean looksLikeMusic(Context context, List<Video> videos) {
        int seen = 0;
        int songs = 0;
        for (Video video : videos) {
            if (video == null || video.videoId == null) {
                continue;
            }
            seen++;
            if (looksLikeSong(context, video)) {
                songs++;
            }
            if (seen >= SAMPLE) {
                break;
            }
        }
        return seen > 0 && songs * 10 >= seen * 4;
    }

    static boolean looksLikeSong(Context context, Video video) {
        String author = video.getAuthor();
        if (author != null) {
            String lower = author.toLowerCase(Locale.ROOT);
            if (lower.endsWith(" - topic") || lower.contains("vevo")) {
                return true;
            }
        }
        if (MusicArt.cached(context, video.videoId) != null) {
            return true; // MusicBrainz knows it
        }
        String title = video.getTitle();
        if (TextUtils.isEmpty(title)) {
            return false;
        }
        if (SONG_WORDS.matcher(title).find()) {
            return true;
        }
        long duration = video.getDurationMs();
        boolean songLength = duration <= 0 || (duration >= 90_000 && duration <= 600_000);
        return songLength && (title.contains(" - ") || title.contains(" – ") || title.contains(" — "));
    }
}
