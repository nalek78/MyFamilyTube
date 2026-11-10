package es.myfamilytube;

import android.app.Activity;
import android.os.Bundle;

import com.liskovsoft.smartyoutubetv2.common.app.views.AddDeviceView;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.app.views.WebBrowserView;
import com.liskovsoft.smartyoutubetv2.tv.R;

import es.myfamilytube.ui.FamilyLayouts;
import es.myfamilytube.ui.MobileChannelActivity;
import es.myfamilytube.ui.MobileChannelUploadsActivity;
import es.myfamilytube.ui.MobileHomeActivity;
import es.myfamilytube.ui.MobilePlayerActivity;
import es.myfamilytube.ui.MobileSearchActivity;

/**
 * MyFamilyTube for phones. Everything the TV app sets up (diagnostics, parental control,
 * crash log…) stays; the screens people touch are swapped for phone ones.
 */
public class MobileApplication extends FamilyApplication {
    /** Screens kept from the TV app (dialogs, sign-in…): they work with touch too. */
    private static final Class<?>[] KEPT_VIEWS = {
            AppDialogView.class, SignInView.class, AddDeviceView.class, WebBrowserView.class
    };

    @Override
    public void onCreate() {
        super.onCreate();

        FamilyLayouts.mobile = true;
        FamilyLayouts.profiles = R.layout.mobile_profiles;
        FamilyLayouts.profileItem = R.layout.mobile_profile_item;
        FamilyLayouts.settings = R.layout.mobile_settings;
        FamilyLayouts.settingRow = R.layout.mobile_setting_row;
        FamilyLayouts.channelRow = R.layout.mobile_channel_row;

        ViewManager viewManager = ViewManager.instance(this);
        viewManager.setRoot(MobileHomeActivity.class);
        viewManager.register(BrowseView.class, MobileHomeActivity.class);
        viewManager.register(PlaybackView.class, MobilePlayerActivity.class, MobileHomeActivity.class);
        viewManager.register(SearchView.class, MobileSearchActivity.class, MobileHomeActivity.class);
        viewManager.register(ChannelView.class, MobileChannelActivity.class, MobileHomeActivity.class);
        viewManager.register(ChannelUploadsView.class, MobileChannelUploadsActivity.class, MobileHomeActivity.class);
        for (Class<?> view : KEPT_VIEWS) {
            Class<? extends Activity> activity = viewManager.getActivity(view);
            if (activity != null) {
                viewManager.register(view, activity, MobileHomeActivity.class);
            }
        }

        registerActivityLifecycleCallbacks(new OpenWatcher());
    }

    /** Going from no screen to one means the app was opened: show the welcome screen again. */
    private static final class OpenWatcher implements ActivityLifecycleCallbacks {
        private int mStarted;

        @Override
        public void onActivityStarted(Activity activity) {
            if (mStarted == 0) {
                MobileHomeActivity.onAppForeground();
            }
            mStarted++;
        }

        @Override
        public void onActivityStopped(Activity activity) {
            mStarted = Math.max(0, mStarted - 1);
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }
}
