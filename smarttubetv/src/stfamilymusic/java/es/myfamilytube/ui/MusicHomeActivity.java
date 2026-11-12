package es.myfamilytube.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

import es.myfamilytube.profiles.FamilyAccounts;
import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;

/** The phone screen of My Family Music: choose who is listening (with their Google account). */
public class MusicHomeActivity extends MotherActivity {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private FamilyProfilesOverlay mProfiles;
    private TextView mAvatar;
    private TextView mName;
    private TextView mInfo;

    @Override
    protected void initTheme() {
        setTheme(R.style.MobileTheme);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.music_home);
        mAvatar = findViewById(R.id.family_profile_button);
        mName = findViewById(R.id.music_profile_name);
        mInfo = findViewById(R.id.music_profile_info);

        ViewGroup root = findViewById(R.id.music_root);
        mProfiles = new FamilyProfilesOverlay(this, root, profile -> showProfile());
        findViewById(R.id.music_profile).setOnClickListener(v -> mProfiles.show());
        findViewById(R.id.music_diagnostics).setOnClickListener(v -> FamilyDiagnosticsDialog.show(this));
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.music_version)).setText("Versión " + version);
        } catch (Exception e) {
            // no version text
        }

        // Another profile: its Google account and rules (the car follows at once)
        FamilyProfiles.instance(this).addListener(() -> mHandler.post(() -> {
            if (!isFinishing()) {
                FamilyAccounts.applyFor(this, FamilyProfiles.instance(this).getCurrent());
                showProfile();
            }
        }));
        showProfile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mProfiles.onResumed(); // back from adding a Google account?
        showProfile();
        if (FamilyProfiles.instance(this).shouldAskWhoIsWatching() && !mProfiles.isShowing()) {
            mProfiles.showMandatory();
        }
        mHandler.postDelayed(() -> MobileUpdates.checkOnce(this), 2000);
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mProfiles.onBack()) {
            return;
        }
        super.onBackPressed();
    }

    private void showProfile() {
        FamilyProfile profile = FamilyProfiles.instance(this).getCurrent();
        FamilyAvatars.apply(mAvatar, profile, false, 0);
        mName.setText(profile.name);
        if (profile.isKid) {
            int left = FamilyProfiles.instance(this).getMinutesLeft(profile);
            mInfo.setText("Perfil infantil" + (left >= 0 ? " · quedan " + left + " min hoy" : ""));
        } else {
            mInfo.setText(!android.text.TextUtils.isEmpty(FamilyAccounts.currentKey()) ? "Con su cuenta de Google" : "Sin cuenta de Google");
        }
    }
}
