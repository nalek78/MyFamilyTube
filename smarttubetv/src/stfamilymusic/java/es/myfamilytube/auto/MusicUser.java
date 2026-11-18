package es.myfamilytube.auto;

import android.content.Context;
import android.content.SharedPreferences;

import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;

/**
 * My Family Music has no profiles: one person, with a Google account or without one. The first
 * time, the profiles from before are replaced by a single one (starting from scratch, but keeping
 * the Google account that was in use).
 */
public final class MusicUser {
    private static final String PREFS = "music_user";

    private MusicUser() {
    }

    public static synchronized FamilyProfile ensure(Context context) {
        FamilyProfiles profiles = FamilyProfiles.instance(context);
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString("id", null);
        FamilyProfile user = id != null ? profiles.get(id) : null;
        if (user == null) {
            String account = profiles.getCurrent().accountKey;
            user = profiles.add("Tú", false, 0);
            user.accountKey = account != null ? account : "";
            for (FamilyProfile other : profiles.getAll()) {
                if (!other.id.equals(user.id)) {
                    profiles.remove(other);
                }
            }
            profiles.setAdultPinEnabled(false);
            profiles.update(user);
            prefs.edit().putString("id", user.id).apply();
        }
        if (!user.id.equals(profiles.getCurrent().id)) {
            profiles.setCurrent(user);
        }
        if (!prefs.getBoolean("joined_old", false)) { // what the old profiles listened to, back
            MusicTaste.joinOldProfiles(context, user.id);
            prefs.edit().putBoolean("joined_old", true).apply();
        }
        return user;
    }
}
