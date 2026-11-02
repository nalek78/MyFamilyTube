package es.myfamilytube;

import android.app.Activity;

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
import com.liskovsoft.smartyoutubetv2.tv.ui.main.MainApplication;

import es.myfamilytube.ui.FamilyBrowseActivity;

/**
 * Keeps all the upstream setup and swaps the screens that MyFamilyTube redesigns.
 * The engine (presenters, player, YouTube access) stays exactly as in SmartTube.
 */
public class FamilyApplication extends MainApplication {
    private static final Class<?>[] CHILD_VIEWS = {
            PlaybackView.class, AppDialogView.class, SearchView.class, SignInView.class,
            AddDeviceView.class, ChannelView.class, ChannelUploadsView.class, WebBrowserView.class
    };

    @Override
    public void onCreate() {
        super.onCreate();

        ViewManager viewManager = ViewManager.instance(this);
        viewManager.setRoot(FamilyBrowseActivity.class);
        viewManager.register(BrowseView.class, FamilyBrowseActivity.class);

        // Back from any other screen returns to our home
        for (Class<?> view : CHILD_VIEWS) {
            Class<? extends Activity> activity = viewManager.getActivity(view);
            if (activity != null) {
                viewManager.register(view, activity, FamilyBrowseActivity.class);
            }
        }
    }
}
