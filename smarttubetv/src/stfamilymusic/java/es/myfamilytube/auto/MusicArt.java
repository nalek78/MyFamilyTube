package es.myfamilytube.auto;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The song behind a YouTube video, looked up in the iTunes library: square album art,
 * clean artist and song names, the album and the kind of music. When iTunes doesn't
 * know the song, the YouTube picture and names are used.
 */
public final class MusicArt {
    private static final String PREFS = "music_art";
    private static final long RETRY_NOT_FOUND_MS = 14L * 24 * 60 * 60 * 1000;
    /** iTunes allows about 20 searches a minute. */
    private static final int MAX_PER_MINUTE = 18;
    private static final ArrayDeque<Long> sRecent = new ArrayDeque<>();

    private static final Pattern BRACKETS = Pattern.compile(
            "[\\(\\[【][^\\)\\]】]*(official|oficial|video|vídeo|clip|lyric|letra|audio|hd|4k|visuali[sz]er|mv|remaster|en vivo|live)[^\\)\\]】]*[\\)\\]】]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FEAT = Pattern.compile("\\s*[\\(\\[]?\\s*(ft\\.?|feat\\.?|featuring)\\s+[^\\)\\]\\-–—]*[\\)\\]]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHOR_TAIL = Pattern.compile("(\\s*-\\s*topic|\\s*vevo|\\s+official|\\s+oficial|\\s*tv)$", Pattern.CASE_INSENSITIVE);

    /** What iTunes says about a song (or what could be guessed from YouTube). */
    public static final class Info {
        public String artist;
        public String track;
        public String album;
        public String genre;
        /** Square album art (600 px), or null when iTunes doesn't know the song. */
        public String artUrl;

        boolean found() {
            return artUrl != null;
        }
    }

    private MusicArt() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Already known, without going to the internet (null if never looked up). */
    public static Info cached(Context context, String videoId) {
        if (context == null || TextUtils.isEmpty(videoId)) {
            return null;
        }
        String saved = prefs(context).getString(videoId, null);
        if (saved == null) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(saved);
            if (!json.has("art")) {
                return null; // looked up but not found
            }
            Info info = new Info();
            info.artist = json.optString("artist", null);
            info.track = json.optString("track", null);
            info.album = json.optString("album", null);
            info.genre = json.optString("genre", null);
            info.artUrl = json.optString("art", null);
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Looks the song up in iTunes (slow: never on the main thread). Returns null when iTunes
     * doesn't know it; then {@link #guess} gives the names from YouTube.
     */
    public static Info lookup(Context context, String videoId, String title, String author) {
        if (context == null || TextUtils.isEmpty(videoId) || TextUtils.isEmpty(title)) {
            return null;
        }
        SharedPreferences prefs = prefs(context);
        String saved = prefs.getString(videoId, null);
        if (saved != null) {
            try {
                JSONObject json = new JSONObject(saved);
                if (json.has("art")) {
                    return cached(context, videoId);
                }
                if (System.currentTimeMillis() - json.optLong("time") < RETRY_NOT_FOUND_MS) {
                    return null;
                }
            } catch (Exception e) {
                // look it up again
            }
        }
        if (!takeTurn()) {
            return null; // too many searches right now: YouTube picture this time
        }
        Info guess = guess(title, author);
        try {
            Info info = search(guess);
            JSONObject json = new JSONObject();
            json.put("time", System.currentTimeMillis());
            if (info != null) {
                json.put("artist", info.artist);
                json.put("track", info.track);
                json.put("album", info.album);
                json.put("genre", info.genre);
                json.put("art", info.artUrl);
            }
            prefs.edit().putString(videoId, json.toString()).apply();
            return info;
        } catch (Exception e) {
            return null; // no connection: try again next time
        }
    }

    private static synchronized boolean takeTurn() {
        long now = System.currentTimeMillis();
        while (!sRecent.isEmpty() && now - sRecent.peekFirst() > 60_000) {
            sRecent.pollFirst();
        }
        if (sRecent.size() >= MAX_PER_MINUTE) {
            return false;
        }
        sRecent.addLast(now);
        return true;
    }

    /** Artist and song from a YouTube title like «Artista - Canción (Official Video)». */
    public static Info guess(String title, String author) {
        Info info = new Info();
        String clean = title == null ? "" : BRACKETS.matcher(title).replaceAll("");
        int bar = clean.indexOf('|');
        if (bar > 0) {
            clean = clean.substring(0, bar);
        }
        clean = clean.replace('"', ' ').replace('“', ' ').replace('”', ' ').trim();
        String[] dashes = {" - ", " – ", " — "};
        for (String dash : dashes) {
            int at = clean.indexOf(dash);
            if (at > 0) {
                info.artist = clean.substring(0, at).trim();
                info.track = clean.substring(at + dash.length()).trim();
                break;
            }
        }
        if (info.track == null) {
            info.track = clean;
            info.artist = cleanAuthor(author);
        }
        info.track = FEAT.matcher(info.track).replaceAll("").trim();
        if (info.artist != null) {
            info.artist = FEAT.matcher(info.artist).replaceAll("").trim();
            int comma = info.artist.indexOf(", ");
            int amp = info.artist.indexOf(" & ");
            int cut = comma > 0 ? comma : amp; // first artist only
            if (cut > 0) {
                info.artist = info.artist.substring(0, cut).trim();
            }
        }
        if (TextUtils.isEmpty(info.track)) {
            info.track = title;
        }
        return info;
    }

    public static String cleanAuthor(String author) {
        if (author == null) {
            return null;
        }
        String result = author.trim();
        String before;
        do {
            before = result;
            result = AUTHOR_TAIL.matcher(result).replaceAll("").trim();
        } while (!result.equals(before));
        return result.isEmpty() ? author.trim() : result;
    }

    private static Info search(Info guess) throws Exception {
        String term = (guess.artist != null ? guess.artist + " " : "") + guess.track;
        String url = "https://itunes.apple.com/search?media=music&entity=song&limit=8&country=ES&term=" + Uri.encode(term);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("iTunes " + code); // busy: not saved as «not found»
            }
            JSONArray results = new JSONObject(read(connection.getInputStream())).optJSONArray("results");
            if (results == null) {
                return null;
            }
            for (int i = 0; i < results.length(); i++) {
                JSONObject song = results.optJSONObject(i);
                if (song == null) {
                    continue;
                }
                String artist = song.optString("artistName");
                String track = song.optString("trackName");
                boolean artistOk = guess.artist == null || similar(artist, guess.artist);
                if (artistOk && similar(track, guess.track)) {
                    String art = song.optString("artworkUrl100", null);
                    if (TextUtils.isEmpty(art)) {
                        continue;
                    }
                    Info info = new Info();
                    info.artist = artist;
                    info.track = track;
                    info.album = cleanAlbum(song.optString("collectionName", null));
                    info.genre = song.optString("primaryGenreName", null);
                    info.artUrl = art.replace("100x100bb", "600x600bb");
                    return info;
                }
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }

    private static String cleanAlbum(String album) {
        if (album == null) {
            return null;
        }
        return album.replaceAll("\\s*-\\s*(Single|EP)$", "").replaceAll("\\s*\\((Deluxe|Remastered|Expanded)[^)]*\\)", "").trim();
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Same name, more or less (accents, «feat.», «Remastered»… don't matter). */
    static boolean similar(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        if (na.contains(nb) || nb.contains(na)) {
            return true;
        }
        Set<String> wa = words(na);
        Set<String> wb = words(nb);
        if (wa.isEmpty() || wb.isEmpty()) {
            return false;
        }
        int common = 0;
        for (String word : wa) {
            if (wb.contains(word)) {
                common++;
            }
        }
        return common * 2 >= Math.min(wa.size(), wb.size()) + 1;
    }

    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        for (String word : text.split(" ")) {
            if (word.length() > 1 && !word.equals("the") && !word.equals("el") && !word.equals("la") && !word.equals("los") && !word.equals("las")) {
                words.add(word);
            }
        }
        return words;
    }
}
