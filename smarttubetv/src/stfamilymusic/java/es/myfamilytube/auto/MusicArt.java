package es.myfamilytube.auto;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The song behind a YouTube video, looked up in MusicBrainz (the free music encyclopedia):
 * clean artist and song names, the album, the kind of music (the artist's genres), and the
 * album cover from the Cover Art Archive. When the song isn't found, the YouTube picture and
 * names are used.
 *
 * Limits respected: MusicBrainz allows about 1 request per second from each phone (more, and
 * it refuses everything for a while), and every request must say which app it is and how to
 * contact it (User-Agent). The Cover Art Archive has no limit for now. Covers are for personal
 * use: their rights belong to their owners.
 */
public final class MusicArt {
    private static final String PREFS = "music_art_mb";
    private static final String OLD_PREFS = "music_art"; // iTunes, until 1.0.4
    private static final long RETRY_NOT_FOUND_MS = 14L * 24 * 60 * 60 * 1000;
    /** One MusicBrainz request at a time for the whole app, 1.1 s apart. */
    private static final long GAP_MS = 1100;
    private static final Object sGate = new Object();
    private static long sNextSlot;
    private static String sUserAgent;
    private static boolean sOldCleared;

    private static final Pattern BRACKETS = Pattern.compile(
            "[\\(\\[【][^\\)\\]】]*(official|oficial|video|vídeo|clip|lyric|letra|audio|hd|4k|visuali[sz]er|mv|remaster|en vivo|live)[^\\)\\]】]*[\\)\\]】]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FEAT = Pattern.compile("\\s*[\\(\\[]?\\s*(ft\\.?|feat\\.?|featuring)\\s+[^\\)\\]\\-–—]*[\\)\\]]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHOR_TAIL = Pattern.compile("(\\s*-\\s*topic|\\s*vevo|\\s+official|\\s+oficial|\\s*tv)$", Pattern.CASE_INSENSITIVE);

    /** What MusicBrainz says about a song (or what could be guessed from YouTube). */
    public static final class Info {
        public String artist;
        public String track;
        public String album;
        public String genre;
        /** Square album cover (500 px), or null when the song wasn't found. */
        public String artUrl;
        String artistId;
    }

    private MusicArt() {
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        if (!sOldCleared) {
            sOldCleared = true;
            app.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE).edit().clear().apply(); // no more iTunes
        }
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Already known, without going to the internet (null if not found or never looked up). */
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
            info.artistId = json.optString("artistId", null);
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    /** Already looked up: found, or not found lately (no need to ask now). */
    public static boolean known(Context context, String videoId) {
        if (context == null || TextUtils.isEmpty(videoId)) {
            return true;
        }
        String saved = prefs(context).getString(videoId, null);
        if (saved == null) {
            return false;
        }
        try {
            JSONObject json = new JSONObject(saved);
            return json.has("art") || System.currentTimeMillis() - json.optLong("time") < RETRY_NOT_FOUND_MS;
        } catch (Exception e) {
            return false;
        }
    }

    /** Looks the song up, waiting for its turn as long as needed (never on the main thread). */
    public static Info lookup(Context context, String videoId, String title, String author) {
        return lookup(context, videoId, title, author, 120_000);
    }

    /**
     * Looks the song up (slow: never on the main thread). If its turn would take more than
     * maxWaitMs (many songs waiting), it gives up for now: null, and it is asked another time.
     * With a long wait it also completes the kind of music when it is missing.
     */
    public static Info lookup(Context context, String videoId, String title, String author, long maxWaitMs) {
        if (context == null || TextUtils.isEmpty(videoId) || TextUtils.isEmpty(title)) {
            return null;
        }
        SharedPreferences prefs = prefs(context);
        Info known = cached(context, videoId);
        if (known != null) {
            if (known.genre == null && known.artistId != null && maxWaitMs >= 10_000) {
                known.genre = artistGenre(context, known.artistId, maxWaitMs);
                if (known.genre != null) {
                    save(prefs, videoId, known);
                }
            }
            return known;
        }
        if (known(context, videoId)) {
            return null; // not found lately
        }
        Info guess = guess(title, author);
        try {
            Info info = search(context, guess, maxWaitMs);
            if (info == SKIPPED) {
                return null; // no turn now: another time
            }
            if (info != null && maxWaitMs >= 10_000) {
                info.genre = artistGenre(context, info.artistId, maxWaitMs);
            }
            save(prefs, videoId, info);
            return info;
        } catch (Exception e) {
            return null; // no connection or MusicBrainz busy: try again next time
        }
    }

    private static void save(SharedPreferences prefs, String videoId, Info info) {
        try {
            JSONObject json = new JSONObject();
            json.put("time", System.currentTimeMillis());
            if (info != null) {
                json.put("artist", info.artist);
                json.put("track", info.track);
                json.put("album", info.album);
                json.put("genre", info.genre);
                json.put("art", info.artUrl);
                json.put("artistId", info.artistId);
            }
            prefs.edit().putString(videoId, json.toString()).apply();
        } catch (Exception e) {
            // not saved: asked again next time
        }
    }

    /** Waits for the app's turn to ask MusicBrainz (1 request every 1.1 s); false if too long. */
    private static boolean takeTurn(long maxWaitMs) throws InterruptedException {
        long wait;
        synchronized (sGate) {
            long now = SystemClock.elapsedRealtime();
            long slot = Math.max(now, sNextSlot);
            wait = slot - now;
            if (wait > maxWaitMs) {
                return false;
            }
            sNextSlot = slot + GAP_MS;
        }
        if (wait > 0) {
            Thread.sleep(wait);
        }
        return true;
    }

    /** MusicBrainz said «too many»: nobody asks for 10 s. */
    private static void backOff() {
        synchronized (sGate) {
            sNextSlot = Math.max(sNextSlot, SystemClock.elapsedRealtime()) + 10_000;
        }
    }

    private static String userAgent(Context context) {
        if (sUserAgent == null) {
            String version = "1.0";
            try {
                version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception e) {
                // default
            }
            sUserAgent = "MyFamilyMusic/" + version + " ( https://github.com/nalek78/MyFamilyTube )";
        }
        return sUserAgent;
    }

    private static final Info SKIPPED = new Info();

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

    private static String quote(String text) {
        return "\"" + text.replace("\\", " ").replace("\"", " ") + "\"";
    }

    private static JSONObject askMusicBrainz(Context context, String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", userAgent(context));
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            if (code == 503) {
                backOff();
            }
            if (code != 200) {
                throw new IllegalStateException("MusicBrainz " + code); // not saved as «not found»
            }
            return new JSONObject(read(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    /** The song in MusicBrainz and a cover in the Cover Art Archive. SKIPPED: no turn now. */
    private static Info search(Context context, Info guess, long maxWaitMs) throws Exception {
        if (!takeTurn(maxWaitMs)) {
            return SKIPPED;
        }
        String query = "recording:" + quote(guess.track) + (guess.artist != null ? " AND artist:" + quote(guess.artist) : "");
        JSONObject answer = askMusicBrainz(context,
                "https://musicbrainz.org/ws/2/recording?fmt=json&limit=10&query=" + Uri.encode(query));
        JSONArray recordings = answer.optJSONArray("recordings");
        if (recordings == null) {
            return null;
        }
        for (int i = 0; i < recordings.length(); i++) {
            JSONObject recording = recordings.optJSONObject(i);
            if (recording == null || recording.optInt("score") < 70 || !similar(recording.optString("title"), guess.track)) {
                continue;
            }
            JSONArray credits = recording.optJSONArray("artist-credit");
            JSONObject credit = credits != null ? credits.optJSONObject(0) : null;
            JSONObject artistObject = credit != null ? credit.optJSONObject("artist") : null;
            String artist = credit != null ? credit.optString("name", "") : "";
            if (artist.isEmpty() && artistObject != null) {
                artist = artistObject.optString("name", "");
            }
            if (guess.artist != null && !similar(artist, guess.artist)) {
                continue;
            }
            // The album: an official album first, then singles and EPs, compilations last
            List<String[]> groups = albums(recording.optJSONArray("releases"));
            for (String[] group : groups) {
                String art = "https://coverartarchive.org/release-group/" + group[0] + "/front-500";
                if (hasCover("https://coverartarchive.org/release-group/" + group[0] + "/front-250")) {
                    Info info = new Info();
                    info.artist = artist;
                    info.track = recording.optString("title", guess.track);
                    info.album = cleanAlbum(group[1]);
                    info.artUrl = art;
                    info.artistId = artistObject != null ? artistObject.optString("id", null) : null;
                    return info;
                }
            }
        }
        return null;
    }

    /** Distinct release groups of a recording, the best first: {id, title}. At most 3. */
    private static List<String[]> albums(JSONArray releases) {
        List<String[]> best = new ArrayList<>();
        List<String[]> other = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        if (releases == null) {
            return best;
        }
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                JSONObject group = release != null ? release.optJSONObject("release-group") : null;
                if (group == null || seen.contains(group.optString("id"))) {
                    continue;
                }
                String type = group.optString("primary-type", "");
                JSONArray secondary = group.optJSONArray("secondary-types");
                boolean plain = secondary == null || secondary.length() == 0;
                boolean official = "Official".equals(release.optString("status", "Official"));
                boolean match = pass == 0 ? official && plain && "Album".equals(type)
                        : pass == 1 ? official && plain : true;
                if (match) {
                    seen.add(group.optString("id"));
                    String title = group.optString("title", release.optString("title", null));
                    (pass < 2 ? best : other).add(new String[]{group.optString("id"), title});
                }
            }
        }
        best.addAll(other);
        return best.subList(0, Math.min(3, best.size()));
    }

    /** The Cover Art Archive has a front cover for it (it redirects to archive.org). */
    private static boolean hasCover(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            try {
                return connection.getResponseCode() == 200;
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** The artist's main kind of music, in Spanish (asked once per artist). */
    private static String artistGenre(Context context, String artistId, long maxWaitMs) {
        if (TextUtils.isEmpty(artistId)) {
            return null;
        }
        SharedPreferences prefs = prefs(context);
        String key = "genre:" + artistId;
        if (prefs.contains(key)) {
            String saved = prefs.getString(key, "");
            return saved.isEmpty() ? null : saved;
        }
        try {
            if (!takeTurn(maxWaitMs)) {
                return null;
            }
            JSONObject artist = askMusicBrainz(context, "https://musicbrainz.org/ws/2/artist/" + artistId + "?inc=genres&fmt=json");
            JSONArray genres = artist.optJSONArray("genres");
            String best = null;
            int bestCount = -1;
            if (genres != null) {
                for (int i = 0; i < genres.length(); i++) {
                    JSONObject genre = genres.optJSONObject(i);
                    if (genre != null && genre.optInt("count") > bestCount) {
                        bestCount = genre.optInt("count");
                        best = genre.optString("name", null);
                    }
                }
            }
            String spanish = spanishGenre(best);
            prefs.edit().putString(key, spanish != null ? spanish : "").apply();
            return spanish;
        } catch (Exception e) {
            return null;
        }
    }

    private static final String[][] GENRES = {
            {"pop", "Pop"}, {"latin pop", "Pop latino"}, {"rock", "Rock"}, {"pop rock", "Pop rock"},
            {"reggaeton", "Reguetón"}, {"latin", "Latina"}, {"hip hop", "Hip hop"}, {"rap", "Rap"}, {"trap", "Trap"},
            {"latin trap", "Trap latino"}, {"electronic", "Electrónica"}, {"dance", "Dance"}, {"dance-pop", "Dance pop"},
            {"electropop", "Electropop"}, {"house", "House"}, {"techno", "Techno"}, {"flamenco", "Flamenco"},
            {"rumba", "Rumba"}, {"singer-songwriter", "Cantautor"}, {"indie pop", "Indie pop"}, {"indie rock", "Indie rock"},
            {"indie", "Indie"}, {"alternative rock", "Rock alternativo"}, {"hard rock", "Hard rock"}, {"punk", "Punk"},
            {"heavy metal", "Heavy metal"}, {"metal", "Metal"}, {"r&b", "R&B"}, {"contemporary r&b", "R&B"}, {"soul", "Soul"},
            {"funk", "Funk"}, {"jazz", "Jazz"}, {"blues", "Blues"}, {"classical", "Clásica"}, {"soundtrack", "Bandas sonoras"},
            {"folk", "Folk"}, {"country", "Country"}, {"salsa", "Salsa"}, {"bachata", "Bachata"}, {"cumbia", "Cumbia"},
            {"merengue", "Merengue"}, {"ballad", "Baladas"}, {"bolero", "Bolero"}, {"ranchera", "Ranchera"},
            {"children's music", "Infantil"}, {"christmas music", "Navidad"}, {"k-pop", "K-pop"}, {"disco", "Disco"},
            {"synth-pop", "Synth pop"}, {"new wave", "New wave"}, {"reggae", "Reggae"}, {"edm", "EDM"},
    };

    static String spanishGenre(String genre) {
        if (TextUtils.isEmpty(genre)) {
            return null;
        }
        String lower = genre.toLowerCase(Locale.ROOT);
        for (String[] pair : GENRES) {
            if (pair[0].equals(lower)) {
                return pair[1];
            }
        }
        return Character.toUpperCase(genre.charAt(0)) + genre.substring(1);
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
