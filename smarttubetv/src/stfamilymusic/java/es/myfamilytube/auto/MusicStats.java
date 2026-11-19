package es.myfamilytube.auto;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Anonymous use statistics, sent at most once a day to the owner's Google Sheet (an Apps Script).
 * Only: a random number for this installation (not tied to any person or account), the phone
 * model, Android and app versions, and counters (days used, minutes, songs, Android Auto,
 * Chromecast, searches). Never names, accounts, places nor which songs are heard.
 */
public final class MusicStats {
    /** The Apps Script web app that receives the statistics (empty: nothing is sent). */
    static final String ENDPOINT = "";

    private static final String PREFS = "music_stats";
    private static final long SEND_EVERY_MS = 20L * 60 * 60 * 1000;
    private static final int MAX_DAYS = 60;

    private MusicStats() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static synchronized void add(Context context, String counter, int amount) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit().putInt(counter, prefs.getInt(counter, 0) + amount);
        Set<String> days = new HashSet<>(prefs.getStringSet("days", new HashSet<>()));
        if (days.size() < MAX_DAYS && days.add(today())) {
            editor.putStringSet("days", days);
        }
        editor.apply();
    }

    public static void appOpened(Context context) {
        add(context, "opens", 1);
        sendIfDue(context);
    }

    static void songHeard(Context context) {
        add(context, "songs", 1);
    }

    static void searched(Context context) {
        add(context, "searches", 1);
    }

    static void autoConnected(Context context) {
        add(context, "autoSessions", 1);
    }

    static void castStarted(Context context) {
        add(context, "castSessions", 1);
    }

    /** A minute of music: on the phone, in the car (Android Auto) or on a Chromecast. */
    static void minutePlayed(Context context, boolean casting, boolean inCar, boolean video) {
        add(context, casting ? "minCast" : inCar ? "minAuto" : "minPhone", 1);
        if (video) {
            add(context, "minVideo", 1);
        }
        sendIfDue(context);
    }

    /** The phone is in car mode (Android Auto connected). */
    static boolean inCar(Context context) {
        UiModeManager modes = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        return modes != null && modes.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR;
    }

    static synchronized void sendIfDue(Context context) {
        if (TextUtils.isEmpty(ENDPOINT)) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("lastSend", 0) < SEND_EVERY_MS || now - prefs.getLong("lastTry", 0) < 60 * 60 * 1000) {
            return;
        }
        prefs.edit().putLong("lastTry", now).apply();
        Context app = context.getApplicationContext();
        JSONObject report = report(app);
        new Thread(() -> {
            if (post(report)) {
                clearSent(app, report);
            }
        }, "MusicStats").start();
    }

    private static String installId(SharedPreferences prefs) {
        String id = prefs.getString("id", null);
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 13);
            prefs.edit().putString("id", id).putString("since", today()).apply();
        }
        return id;
    }

    private static final String[] COUNTERS = {"opens", "songs", "searches", "autoSessions", "castSessions",
            "minPhone", "minAuto", "minCast", "minVideo"};

    private static synchronized JSONObject report(Context context) {
        SharedPreferences prefs = prefs(context);
        JSONObject json = new JSONObject();
        try {
            json.put("id", installId(prefs));
            json.put("since", prefs.getString("since", today()));
            json.put("maker", Build.MANUFACTURER);
            json.put("model", Build.MODEL);
            json.put("android", Build.VERSION.RELEASE);
            json.put("sdk", Build.VERSION.SDK_INT);
            String version = "";
            try {
                version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception e) {
                // unknown
            }
            json.put("version", version);
            json.put("language", Locale.getDefault().getLanguage());
            for (String counter : COUNTERS) {
                json.put(counter, prefs.getInt(counter, 0));
            }
            json.put("days", new JSONArray(prefs.getStringSet("days", new HashSet<>())));
            json.put("account", !TextUtils.isEmpty(es.myfamilytube.profiles.FamilyAccounts.currentKey()));
            json.put("localLists", LocalLists.all(context).size());
            json.put("tastes", !MusicPrefs.isEmpty(context));
            json.put("songsKnown", MusicTaste.of(context, MusicUser.ensure(context).id).recent(400).size());
        } catch (Exception e) {
            // what could be filled is sent
        }
        return json;
    }

    /** What was sent is taken off the counters (what happened meanwhile stays). */
    private static synchronized void clearSent(Context context, JSONObject sent) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        for (String counter : COUNTERS) {
            editor.putInt(counter, Math.max(0, prefs.getInt(counter, 0) - sent.optInt(counter)));
        }
        Set<String> days = new HashSet<>(prefs.getStringSet("days", new HashSet<>()));
        JSONArray sentDays = sent.optJSONArray("days");
        String today = today();
        for (int i = 0; sentDays != null && i < sentDays.length(); i++) {
            if (!today.equals(sentDays.optString(i))) {
                days.remove(sentDays.optString(i));
            }
        }
        editor.putStringSet("days", days).putLong("lastSend", System.currentTimeMillis()).apply();
    }

    private static boolean post(JSONObject report) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false); // Apps Script answers with a redirect once saved
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(report.toString().getBytes("UTF-8"));
            }
            int code = connection.getResponseCode();
            connection.disconnect();
            return code == 200 || code == 302;
        } catch (Exception e) {
            return false;
        }
    }
}
