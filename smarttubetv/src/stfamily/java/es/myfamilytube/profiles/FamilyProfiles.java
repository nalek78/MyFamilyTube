package es.myfamilytube.profiles;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Profiles stored on this TV only (no Google account needed). */
public final class FamilyProfiles {
    private static final String PREFS = "family_profiles";
    private static final String KEY_LIST = "profiles";
    private static final String KEY_CURRENT = "current";
    private static final String KEY_PIN = "parental_pin";
    private static FamilyProfiles sInstance;
    /** True once someone picked a profile in this run of the app. */
    private static boolean sChosenThisRun;

    private final SharedPreferences mPrefs;
    private final List<FamilyProfile> mProfiles = new ArrayList<>();
    private final List<Runnable> mListeners = new ArrayList<>();

    private FamilyProfiles(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
        if (mProfiles.isEmpty()) {
            // First run: one adult profile so the app works straight away
            add("Principal", false, FamilyProfile.AGE_5_TO_8);
        }
    }

    public static synchronized FamilyProfiles instance(Context context) {
        if (sInstance == null) {
            sInstance = new FamilyProfiles(context);
        }
        return sInstance;
    }

    public List<FamilyProfile> getAll() {
        return new ArrayList<>(mProfiles);
    }

    public FamilyProfile get(String id) {
        for (FamilyProfile profile : mProfiles) {
            if (profile.id.equals(id)) {
                return profile;
            }
        }
        return null;
    }

    public FamilyProfile getCurrent() {
        FamilyProfile current = get(mPrefs.getString(KEY_CURRENT, ""));
        return current != null ? current : mProfiles.get(0);
    }

    public void setCurrent(FamilyProfile profile) {
        mPrefs.edit().putString(KEY_CURRENT, profile.id).apply();
        sChosenThisRun = true;
        for (Runnable listener : mListeners) {
            listener.run();
        }
    }

    /** Called whenever the current profile changes or a profile's settings are saved. */
    public void addListener(Runnable listener) {
        mListeners.add(listener);
    }

    /** The app came to the front (opened, or back from the background): ask who is watching again. */
    public static void onAppForeground() {
        sChosenThisRun = false;
    }

    /** The picker is shown at start when there is more than one person to choose from. */
    public boolean shouldAskWhoIsWatching() {
        return !sChosenThisRun && mProfiles.size() > 1;
    }

    public FamilyProfile add(String name, boolean isKid, int ageGroup) {
        int color = FamilyProfile.COLORS[mProfiles.size() % FamilyProfile.COLORS.length];
        FamilyProfile profile = new FamilyProfile(UUID.randomUUID().toString(), name, color, isKid, ageGroup);
        if (isKid) {
            profile.applyAgeDefaults();
        }
        mProfiles.add(profile);
        save();
        return profile;
    }

    public void update(FamilyProfile profile) {
        save();
        // Only the profile in use changes what is on screen
        if (profile.id.equals(getCurrent().id)) {
            for (Runnable listener : mListeners) {
                listener.run();
            }
        }
    }

    /** The last adult profile can't be removed, so there is always someone to manage the others. */
    public boolean canRemove(FamilyProfile profile) {
        if (profile.isKid) {
            return true;
        }
        int adults = 0;
        for (FamilyProfile p : mProfiles) {
            if (!p.isKid) {
                adults++;
            }
        }
        return adults > 1;
    }

    public void remove(FamilyProfile profile) {
        if (!canRemove(profile)) {
            return;
        }
        mProfiles.remove(profile);
        if (profile.id.equals(mPrefs.getString(KEY_CURRENT, ""))) {
            mPrefs.edit().remove(KEY_CURRENT).apply();
        }
        save();
    }

    /** Ask the parental PIN to enter a grown-up's profile (default on, so kids can't just pick one). */
    public boolean isAdultPinEnabled() {
        return mPrefs.getBoolean("adult_pin", true);
    }

    public void setAdultPinEnabled(boolean enabled) {
        mPrefs.edit().putBoolean("adult_pin", enabled).apply();
    }

    /** Whether choosing this profile needs the PIN. */
    public boolean needsPinFor(FamilyProfile profile) {
        return !profile.isKid && hasPin() && isAdultPinEnabled() && hasKids();
    }

    public boolean hasKids() {
        for (FamilyProfile p : mProfiles) {
            if (p.isKid) {
                return true;
            }
        }
        return false;
    }

    public boolean hasPin() {
        return mPrefs.contains(KEY_PIN);
    }

    public boolean checkPin(String pin) {
        return pin != null && pin.equals(mPrefs.getString(KEY_PIN, null));
    }

    public void setPin(String pin) {
        mPrefs.edit().putString(KEY_PIN, pin).apply();
    }

    // ---------------------------------------------------------------- daily watch time (kids)

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    /** Seconds watched today, minus any extra time granted with the PIN. */
    public int getUsedSecondsToday(FamilyProfile profile) {
        String day = today();
        int used = mPrefs.getInt("usage_" + profile.id + "_" + day, 0);
        int bonus = mPrefs.getInt("bonus_" + profile.id + "_" + day, 0);
        return Math.max(0, used - bonus);
    }

    public void addUsedSeconds(FamilyProfile profile, int seconds) {
        String key = "usage_" + profile.id + "_" + today();
        mPrefs.edit().putInt(key, mPrefs.getInt(key, 0) + seconds).apply();
    }

    public void grantExtraMinutes(FamilyProfile profile, int minutes) {
        String key = "bonus_" + profile.id + "_" + today();
        mPrefs.edit().putInt(key, mPrefs.getInt(key, 0) + minutes * 60).apply();
    }

    /** True when a kid with a daily limit has used it all today. */
    public boolean isTimeUp(FamilyProfile profile) {
        return profile.isKid && profile.dailyLimitMin > 0
                && getUsedSecondsToday(profile) >= profile.dailyLimitMin * 60;
    }

    public int getMinutesLeft(FamilyProfile profile) {
        if (!profile.isKid || profile.dailyLimitMin <= 0) {
            return -1;
        }
        int left = profile.dailyLimitMin * 60 - getUsedSecondsToday(profile);
        return Math.max(0, (left + 59) / 60);
    }

    private void load() {
        mProfiles.clear();
        try {
            JSONArray array = new JSONArray(mPrefs.getString(KEY_LIST, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) {
                    mProfiles.add(FamilyProfile.fromJson(json));
                }
            }
        } catch (JSONException e) {
            // Corrupted data: start again with a default profile
        }
    }

    private void save() {
        JSONArray array = new JSONArray();
        try {
            for (FamilyProfile profile : mProfiles) {
                array.put(profile.toJson());
            }
        } catch (JSONException e) {
            return;
        }
        mPrefs.edit().putString(KEY_LIST, array.toString()).apply();
    }
}
