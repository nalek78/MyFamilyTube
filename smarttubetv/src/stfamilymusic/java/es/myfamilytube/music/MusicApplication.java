package es.myfamilytube.music;

import android.app.Activity;

import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.views.AddDeviceView;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.app.views.WebBrowserView;
import com.liskovsoft.smartyoutubetv2.tv.R;

import es.myfamilytube.FamilyApplication;
import es.myfamilytube.ui.FamilyLayouts;
import es.myfamilytube.ui.MobileSignInActivity;
import es.myfamilytube.ui.MusicHomeActivity;

/**
 * My Family Music: the same YouTube engine, profiles and parental control as MyFamilyTube,
 * but only the music for Android Auto. On the phone it just has its own small screen.
 */
public class MusicApplication extends FamilyApplication {
    private static final Class<?>[] KEPT_VIEWS = {AppDialogView.class, AddDeviceView.class, WebBrowserView.class};

    @Override
    public void onCreate() {
        super.onCreate();

        // What the start screen of the other apps prepares before anything talks to YouTube
        // (here Android Auto may start the app without opening any screen)
        try {
            RxHelper.setupGlobalErrorHandler();
            GlobalPreferences.instance(this);
        } catch (RuntimeException e) {
            // keeps going; the screens retry later
        }

        FamilyLayouts.mobile = true;
        com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity.sTvDensity = false;
        FamilyLayouts.profiles = R.layout.mobile_profiles;
        FamilyLayouts.profileItem = R.layout.mobile_profile_item;
        FamilyLayouts.settings = R.layout.mobile_settings;
        FamilyLayouts.settingRow = R.layout.mobile_setting_row;
        FamilyLayouts.channelRow = R.layout.mobile_channel_row;

        ViewManager viewManager = ViewManager.instance(this);
        viewManager.setRoot(MusicHomeActivity.class);
        viewManager.register(SignInView.class, MobileSignInActivity.class, MusicHomeActivity.class);
        for (Class<?> view : KEPT_VIEWS) {
            Class<? extends Activity> activity = viewManager.getActivity(view);
            if (activity != null) {
                viewManager.register(view, activity, MusicHomeActivity.class);
            }
        }
    }
}
