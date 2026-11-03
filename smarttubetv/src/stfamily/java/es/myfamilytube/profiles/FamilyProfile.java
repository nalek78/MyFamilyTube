package es.myfamilytube.profiles;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** A person who watches on this TV. Kids profiles carry the parental-control settings. */
public final class FamilyProfile {
    public static final int AGE_UP_TO_4 = 0;
    public static final int AGE_5_TO_8 = 1;
    public static final int AGE_9_TO_12 = 2;
    public static final int AGE_13_TO_15 = 3;

    /** Daily limits offered in the editor, in minutes (0 = no limit). */
    public static final int[] LIMITS_MIN = {0, 30, 60, 120};

    /** Avatar colours, picked in order for new profiles. */
    public static final int[] COLORS = {0xFF3D6FD9, 0xFF9B4DCA, 0xFF2E9E6B, 0xFFE08A1E, 0xFFD9534F, 0xFF2A8FA0};

    public final String id;
    public String name;
    public int color;
    public boolean isKid;
    public int ageGroup;
    /** Kids: only videos from approved channels. */
    public boolean approvedOnly;
    /** Kids: minutes per day, 0 = no limit. */
    public int dailyLimitMin;
    /** Kids: hide comments and live chat in the player. */
    public boolean hideComments = true;
    /** Kids: approved channels, channelId -> channel name. */
    public final Map<String, String> approvedChannels = new LinkedHashMap<>();

    public FamilyProfile(String id, String name, int color, boolean isKid, int ageGroup) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.isKid = isKid;
        this.ageGroup = ageGroup;
    }

    /** Starting point for a new kids profile (or when the age changes). */
    public void applyAgeDefaults() {
        switch (ageGroup) {
            case AGE_UP_TO_4: dailyLimitMin = 60; break;
            case AGE_5_TO_8: dailyLimitMin = 60; break;
            case AGE_9_TO_12: dailyLimitMin = 120; break;
            default: dailyLimitMin = 0; break;
        }
        hideComments = true;
    }

    public String getInitial() {
        String trimmed = name != null ? name.trim() : "";
        return trimmed.isEmpty() ? "?" : trimmed.substring(0, 1).toUpperCase();
    }

    public String getDescription() {
        if (!isKid) {
            return "Adulto";
        }
        switch (ageGroup) {
            case AGE_UP_TO_4: return "Infantil · hasta 4 años";
            case AGE_5_TO_8: return "Infantil · 5 a 8 años";
            case AGE_9_TO_12: return "Infantil · 9 a 12 años";
            default: return "Infantil · 13 a 15 años";
        }
    }

    public boolean isChannelApproved(String channelId) {
        return channelId != null && approvedChannels.containsKey(channelId);
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("color", color);
        json.put("kid", isKid);
        json.put("age", ageGroup);
        json.put("approvedOnly", approvedOnly);
        json.put("limit", dailyLimitMin);
        json.put("hideComments", hideComments);
        JSONObject channels = new JSONObject();
        for (Map.Entry<String, String> entry : approvedChannels.entrySet()) {
            channels.put(entry.getKey(), entry.getValue());
        }
        json.put("channels", channels);
        return json;
    }

    static FamilyProfile fromJson(JSONObject json) {
        FamilyProfile profile = new FamilyProfile(
                json.optString("id"),
                json.optString("name"),
                json.optInt("color", COLORS[0]),
                json.optBoolean("kid", false),
                json.optInt("age", AGE_5_TO_8));
        profile.approvedOnly = json.optBoolean("approvedOnly", false);
        profile.dailyLimitMin = json.optInt("limit", 0);
        profile.hideComments = json.optBoolean("hideComments", true);
        JSONObject channels = json.optJSONObject("channels");
        if (channels != null) {
            Iterator<String> keys = channels.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                profile.approvedChannels.put(key, channels.optString(key));
            }
        }
        return profile;
    }
}
