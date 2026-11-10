package es.myfamilytube.ui;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Layouts used by the screens TV and phone share (profiles, settings).
 * The TV ones are the default; the phone app swaps in its own at start.
 */
public final class FamilyLayouts {
    /** True in the phone app: hides TV-only options (thumbnail size, preview on focus). */
    public static boolean mobile = false;

    public static int profiles = R.layout.family_profiles;
    public static int profileItem = R.layout.family_profile_item;
    public static int settings = R.layout.family_settings;
    public static int settingRow = R.layout.family_setting_row;
    public static int channelRow = R.layout.family_tab;

    private FamilyLayouts() {
    }
}
