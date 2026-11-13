package es.myfamilytube.auto;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each profile listens to (only on this phone): the songs played more than half a minute,
 * with their artist, album and kind of music. The tabs are built from it.
 */
final class MusicTaste {
    private static final String PREFS = "music_taste";
    private static final int MAX_SONGS = 400;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    static final class Song {
        String videoId;
        String title;      // the YouTube title (to play it again)
        String author;     // the YouTube channel
        String channelId;
        String image;      // the YouTube picture
        String track;
        String artist;
        String album;
        String genre;
        int plays;
        long last;

        double score(long now) {
            double days = Math.max(0, (now - last) / (double) DAY_MS);
            return plays / (1 + days / 30); // what is listened to now counts more
        }
    }

    /** An artist, album or kind of music, with how much it is listened to. */
    static final class Entry {
        final String name;
        final String artist; // for an album
        double score;
        final List<Song> songs = new ArrayList<>();

        Entry(String name, String artist) {
            this.name = name;
            this.artist = artist;
        }
    }

    private final Context mContext;
    private final String mProfileId;
    private final Map<String, Song> mSongs = new LinkedHashMap<>();

    private MusicTaste(Context context, String profileId) {
        mContext = context.getApplicationContext();
        mProfileId = profileId;
        load();
    }

    private static final Map<String, MusicTaste> sInstances = new HashMap<>();

    static synchronized MusicTaste of(Context context, String profileId) {
        String key = profileId != null ? profileId : "";
        MusicTaste taste = sInstances.get(key);
        if (taste == null) {
            taste = new MusicTaste(context, key);
            sInstances.put(key, taste);
        }
        return taste;
    }

    private SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void load() {
        String saved = prefs().getString(mProfileId, null);
        if (saved == null) {
            return;
        }
        try {
            JSONArray array = new JSONArray(saved);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.getJSONObject(i);
                Song song = new Song();
                song.videoId = json.getString("id");
                song.title = json.optString("t", null);
                song.author = json.optString("au", null);
                song.channelId = json.optString("ch", null);
                song.image = json.optString("img", null);
                song.track = json.optString("tr", null);
                song.artist = json.optString("ar", null);
                song.album = json.optString("al", null);
                song.genre = json.optString("g", null);
                song.plays = json.optInt("n", 1);
                song.last = json.optLong("last");
                mSongs.put(song.videoId, song);
            }
        } catch (Exception e) {
            // broken: start again
        }
    }

    private void save() {
        JSONArray array = new JSONArray();
        try {
            for (Song song : mSongs.values()) {
                JSONObject json = new JSONObject();
                json.put("id", song.videoId);
                json.put("t", song.title);
                json.put("au", song.author);
                json.put("ch", song.channelId);
                json.put("img", song.image);
                json.put("tr", song.track);
                json.put("ar", song.artist);
                json.put("al", song.album);
                json.put("g", song.genre);
                json.put("n", song.plays);
                json.put("last", song.last);
                array.put(json);
            }
        } catch (Exception e) {
            return;
        }
        prefs().edit().putString(mProfileId, array.toString()).apply();
    }

    /** A song has been listened to (info: what MusicBrainz knows about it, may be null). */
    synchronized void record(String videoId, String title, String author, String channelId, String image, MusicArt.Info info) {
        if (TextUtils.isEmpty(videoId)) {
            return;
        }
        Song song = mSongs.remove(videoId);
        if (song == null) {
            song = new Song();
            song.videoId = videoId;
        }
        song.title = title;
        song.author = author;
        song.channelId = channelId;
        if (image != null) {
            song.image = image;
        }
        MusicArt.Info names = info != null ? info : MusicArt.guess(title, author);
        song.track = names.track;
        song.artist = names.artist;
        if (info != null) {
            song.album = info.album;
            song.genre = info.genre;
        }
        song.plays++;
        song.last = System.currentTimeMillis();
        mSongs.put(videoId, song); // the last one at the end
        while (mSongs.size() > MAX_SONGS) {
            mSongs.remove(lowest());
        }
        save();
    }

    private String lowest() {
        long now = System.currentTimeMillis();
        String worst = null;
        double worstScore = Double.MAX_VALUE;
        for (Song song : mSongs.values()) {
            double score = song.score(now);
            if (score < worstScore) {
                worstScore = score;
                worst = song.videoId;
            }
        }
        return worst;
    }

    /** Already listened to (it is not «new» for this profile). */
    synchronized boolean knows(String videoId) {
        return mSongs.containsKey(videoId);
    }

    synchronized boolean isEmpty() {
        return mSongs.isEmpty();
    }

    /** The last songs listened to, the newest first. */
    synchronized List<Song> recent(int max) {
        List<Song> songs = new ArrayList<>(mSongs.values());
        Collections.sort(songs, (a, b) -> Long.compare(b.last, a.last));
        return songs.subList(0, Math.min(max, songs.size()));
    }

    synchronized List<Entry> topArtists(int max) {
        return top(KIND_ARTIST, max, 1);
    }

    synchronized List<Entry> topGenres(int max) {
        return top(KIND_GENRE, max, 2);
    }

    /** Albums with at least two songs listened to, or one listened to a lot. */
    synchronized List<Entry> topAlbums(int max) {
        List<Entry> albums = top(KIND_ALBUM, max * 3, 1);
        List<Entry> result = new ArrayList<>();
        for (Entry album : albums) {
            int plays = 0;
            for (Song song : album.songs) {
                plays += song.plays;
            }
            if ((album.songs.size() >= 2 || plays >= 3) && result.size() < max) {
                result.add(album);
            }
        }
        return result;
    }

    synchronized List<Song> songsOf(int kind, String name) {
        List<Song> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Song song : mSongs.values()) {
            String value = valueOf(song, kind);
            if (value != null && MusicArt.normalize(value).equals(MusicArt.normalize(name))) {
                result.add(song);
            }
        }
        Collections.sort(result, (a, b) -> Double.compare(b.score(now), a.score(now)));
        return result;
    }

    /** Artists of a kind of music, the most listened first. */
    synchronized List<Entry> artistsOfGenre(String genre, int max) {
        Map<String, Entry> artists = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (Song song : songsOf(KIND_GENRE, genre)) {
            if (song.artist == null) {
                continue;
            }
            String key = MusicArt.normalize(song.artist);
            Entry entry = artists.get(key);
            if (entry == null) {
                entry = new Entry(song.artist, null);
                artists.put(key, entry);
            }
            entry.score += song.score(now);
            entry.songs.add(song);
        }
        List<Entry> result = new ArrayList<>(artists.values());
        Collections.sort(result, (a, b) -> Double.compare(b.score, a.score));
        return result.subList(0, Math.min(max, result.size()));
    }

    static final int KIND_ARTIST = 0;
    static final int KIND_ALBUM = 1;
    static final int KIND_GENRE = 2;

    private static String valueOf(Song song, int kind) {
        switch (kind) {
            case KIND_ARTIST:
                return song.artist;
            case KIND_ALBUM:
                return song.album;
            default:
                return song.genre;
        }
    }

    private List<Entry> top(int kind, int max, int minSongs) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (Song song : mSongs.values()) {
            String value = valueOf(song, kind);
            if (TextUtils.isEmpty(value)) {
                continue;
            }
            String key = MusicArt.normalize(value) + (kind == KIND_ALBUM ? "|" + MusicArt.normalize(song.artist) : "");
            Entry entry = entries.get(key);
            if (entry == null) {
                entry = new Entry(value, kind == KIND_ALBUM ? song.artist : null);
                entries.put(key, entry);
            }
            entry.score += song.score(now);
            entry.songs.add(song);
        }
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.songs.size() >= minSongs) {
                Collections.sort(entry.songs, (a, b) -> Double.compare(b.score(now), a.score(now)));
                result.add(entry);
            }
        }
        Collections.sort(result, (a, b) -> Double.compare(b.score, a.score));
        return result.subList(0, Math.min(max, result.size()));
    }
}
