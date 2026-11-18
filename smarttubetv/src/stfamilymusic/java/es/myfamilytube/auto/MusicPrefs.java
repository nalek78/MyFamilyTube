package es.myfamilytube.auto;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * What you told the app you like (styles and artists), to recommend music from the first day,
 * before it has learnt from what you listen to (and without a Google account).
 */
public final class MusicPrefs {
    private static final String PREFS = "music_prefs";

    /** The styles offered (shown on the phone; searched in YouTube with «música»). */
    public static final String[] STYLES = {"Pop", "Pop español", "Rock", "Rock español", "Reggaetón", "Latino", "Flamenco",
            "Indie", "Electrónica", "Dance", "Hip hop", "Rap español", "R&B", "Jazz", "Blues", "Clásica", "Metal", "Baladas",
            "Salsa", "Bachata", "Cumbia", "Country", "Soul", "Funk", "Años 80", "Años 90", "Años 2000", "Bandas sonoras",
            "Infantil", "Villancicos"};

    private MusicPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Already asked (answered or skipped). */
    public static boolean asked(Context context) {
        return prefs(context).getBoolean("asked", false);
    }

    public static void setAsked(Context context) {
        prefs(context).edit().putBoolean("asked", true).apply();
    }

    public static List<String> styles(Context context) {
        return split(prefs(context).getString("styles", ""));
    }

    public static List<String> artists(Context context) {
        return split(prefs(context).getString("artists", ""));
    }

    public static boolean isEmpty(Context context) {
        return styles(context).isEmpty() && artists(context).isEmpty();
    }

    public static void save(Context context, List<String> styles, List<String> artists) {
        prefs(context).edit().putString("styles", TextUtils.join("\n", styles))
                .putString("artists", TextUtils.join("\n", artists)).putBoolean("asked", true).apply();
    }

    /** «Rosalía, Coldplay y Melendi» → three artists. */
    public static List<String> parseArtists(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String part : text.split("[,;\\n]|\\s+y\\s+")) {
            String name = part.trim();
            if (!name.isEmpty() && !result.contains(name) && result.size() < 20) {
                result.add(name);
            }
        }
        return result;
    }

    private static List<String> split(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split("\n")) {
            if (!part.trim().isEmpty()) {
                result.add(part.trim());
            }
        }
        return result;
    }
}
