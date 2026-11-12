package es.myfamilytube.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.liskovsoft.appupdatechecker2.AppUpdateChecker;
import com.liskovsoft.appupdatechecker2.AppUpdateCheckerListener;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.List;

/**
 * Phone: looks for a new version every time the app opens and offers it in a normal dialog
 * (the TV-style notice of the engine doesn't show on the phone screens).
 */
public final class MobileUpdates implements AppUpdateCheckerListener {
    private static final String PREFS = "family_ui";
    private static final String KEY_LATER_VERSION = "update_later_version";
    private static final String KEY_LATER_TIME = "update_later_time";
    private static final long LATER_MS = 24 * 60 * 60 * 1000L;
    private static boolean sCheckedThisRun;

    private final Activity mActivity;
    private final AppUpdateChecker mChecker;

    private MobileUpdates(Activity activity) {
        mActivity = activity;
        mChecker = new AppUpdateChecker(activity.getApplicationContext(), this);
    }

    /** Once each time the app is opened. */
    public static void checkOnce(Activity activity) {
        if (sCheckedThisRun) {
            return;
        }
        sCheckedThisRun = true;
        try {
            MobileUpdates updates = new MobileUpdates(activity);
            updates.mChecker.forceCheckForUpdates(activity.getResources().getStringArray(R.array.update_urls));
        } catch (RuntimeException e) {
            // no update check this time
        }
    }

    @Override
    public void onUpdateFound(String versionName, List<String> changelog, String apkPath) {
        mActivity.runOnUiThread(() -> show(versionName, changelog));
    }

    private void show(String versionName, List<String> changelog) {
        if (mActivity.isFinishing()) {
            return;
        }
        SharedPreferences prefs = mActivity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        boolean postponed = TextUtils.equals(versionName, prefs.getString(KEY_LATER_VERSION, null))
                && System.currentTimeMillis() - prefs.getLong(KEY_LATER_TIME, 0) < LATER_MS;
        if (postponed) {
            return;
        }
        StringBuilder message = new StringBuilder("Hay una versión nueva de " + mActivity.getString(R.string.app_name) + " (" + versionName + ").");
        if (changelog != null && !changelog.isEmpty()) {
            message.append("\n");
            for (String change : changelog) {
                message.append("\n• ").append(change);
            }
        }
        new AlertDialog.Builder(mActivity)
                .setTitle("Actualización disponible")
                .setMessage(message)
                .setPositiveButton("Actualizar", (dialog, which) -> {
                    try {
                        mChecker.installUpdate();
                    } catch (RuntimeException e) {
                        // the installer couldn't open
                    }
                })
                .setNegativeButton("Más tarde", (dialog, which) -> prefs.edit()
                        .putString(KEY_LATER_VERSION, versionName)
                        .putLong(KEY_LATER_TIME, System.currentTimeMillis())
                        .apply())
                .show();
    }

    @Override
    public void onUpdateError(Exception error) {
        // Already up to date or no connection: nothing to say
    }
}
