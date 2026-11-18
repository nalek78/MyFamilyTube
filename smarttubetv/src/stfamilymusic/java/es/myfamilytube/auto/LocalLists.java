package es.myfamilytube.auto;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Song lists kept only on this phone, with no Google account. Each list: a name and its songs
 * (video, title, artist and picture, so they can be shown without asking YouTube).
 */
public final class LocalLists {
    private static final String PREFS = "local_lists";
    private static final String KEY = "lists";
    private static final int MAX_SONGS = 1000;

    public static final class Entry {
        public final String id;
        public final String name;
        public final int size;
        public final String firstVideo;

        Entry(String id, String name, int size, String firstVideo) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.firstVideo = firstVideo;
        }
    }

    private LocalLists() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized JSONArray read(Context context) {
        try {
            return new JSONArray(prefs(context).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static synchronized void write(Context context, JSONArray lists) {
        prefs(context).edit().putString(KEY, lists.toString()).apply();
    }

    private static JSONObject find(JSONArray lists, String id) {
        for (int i = 0; i < lists.length(); i++) {
            JSONObject list = lists.optJSONObject(i);
            if (list != null && list.optString("id").equals(id)) {
                return list;
            }
        }
        return null;
    }

    public static synchronized List<Entry> all(Context context) {
        List<Entry> result = new ArrayList<>();
        JSONArray lists = read(context);
        for (int i = 0; i < lists.length(); i++) {
            JSONObject list = lists.optJSONObject(i);
            if (list == null) {
                continue;
            }
            JSONArray songs = list.optJSONArray("songs");
            String first = songs != null && songs.length() > 0 ? songs.optJSONObject(0).optString("v") : null;
            result.add(new Entry(list.optString("id"), list.optString("name"), songs != null ? songs.length() : 0, first));
        }
        return result;
    }

    public static synchronized String name(Context context, String id) {
        JSONObject list = find(read(context), id);
        return list != null ? list.optString("name") : null;
    }

    /** The list with that name (ignoring case), or null. */
    public static synchronized String idOf(Context context, String name) {
        for (Entry entry : all(context)) {
            if (entry.name.equalsIgnoreCase(name)) {
                return entry.id;
            }
        }
        return null;
    }

    public static synchronized String create(Context context, String name) {
        JSONArray lists = read(context);
        String id = UUID.randomUUID().toString().substring(0, 12);
        try {
            lists.put(new JSONObject().put("id", id).put("name", name).put("songs", new JSONArray()));
        } catch (Exception e) {
            return null;
        }
        write(context, lists);
        return id;
    }

    public static synchronized void rename(Context context, String id, String name) {
        JSONArray lists = read(context);
        JSONObject list = find(lists, id);
        if (list != null) {
            try {
                list.put("name", name);
            } catch (Exception e) {
                return;
            }
            write(context, lists);
        }
    }

    public static synchronized void delete(Context context, String id) {
        JSONArray lists = read(context);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < lists.length(); i++) {
            JSONObject list = lists.optJSONObject(i);
            if (list != null && !list.optString("id").equals(id)) {
                kept.put(list);
            }
        }
        write(context, kept);
    }

    public static synchronized boolean contains(Context context, String id, String videoId) {
        JSONObject list = find(read(context), id);
        JSONArray songs = list != null ? list.optJSONArray("songs") : null;
        if (songs == null) {
            return false;
        }
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song != null && videoId.equals(song.optString("v"))) {
                return true;
            }
        }
        return false;
    }

    /** At the end of the list (if it isn't there already). */
    public static synchronized boolean add(Context context, String id, String videoId, String title, String artist, String image) {
        if (TextUtils.isEmpty(videoId)) {
            return false;
        }
        JSONArray lists = read(context);
        JSONObject list = find(lists, id);
        if (list == null) {
            return false;
        }
        try {
            JSONArray songs = list.optJSONArray("songs");
            if (songs == null) {
                songs = new JSONArray();
                list.put("songs", songs);
            }
            for (int i = 0; i < songs.length(); i++) {
                JSONObject song = songs.optJSONObject(i);
                if (song != null && videoId.equals(song.optString("v"))) {
                    return true;
                }
            }
            if (songs.length() >= MAX_SONGS) {
                return false;
            }
            songs.put(new JSONObject().put("v", videoId).put("t", title != null ? title : "")
                    .put("a", artist != null ? artist : "").put("i", image != null ? image : ""));
        } catch (Exception e) {
            return false;
        }
        write(context, lists);
        return true;
    }

    public static synchronized void remove(Context context, String id, String videoId) {
        JSONArray lists = read(context);
        JSONObject list = find(lists, id);
        JSONArray songs = list != null ? list.optJSONArray("songs") : null;
        if (songs == null) {
            return;
        }
        JSONArray kept = new JSONArray();
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song != null && !videoId.equals(song.optString("v"))) {
                kept.put(song);
            }
        }
        try {
            list.put("songs", kept);
        } catch (Exception e) {
            return;
        }
        write(context, lists);
    }

    static synchronized List<Video> videos(Context context, String id) {
        List<Video> result = new ArrayList<>();
        JSONObject list = find(read(context), id);
        JSONArray songs = list != null ? list.optJSONArray("songs") : null;
        if (songs == null) {
            return result;
        }
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) {
                continue;
            }
            Video video = new Video();
            video.videoId = song.optString("v");
            video.title = song.optString("t");
            video.author = song.optString("a");
            String image = song.optString("i");
            video.cardImageUrl = !TextUtils.isEmpty(image) ? image : "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg";
            result.add(video);
        }
        return result;
    }
}
