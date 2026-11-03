package es.myfamilytube;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

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

import es.myfamilytube.profiles.FamilyProfiles;
import es.myfamilytube.profiles.FamilyRules;
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

    private static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashRecorder();
        FamilyRules.install(this);
        registerActivityLifecycleCallbacks(new ForegroundWatcher());

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

    /** Counts visible screens; going from none to one means the app was opened again. */
    private static final class ForegroundWatcher implements ActivityLifecycleCallbacks {
        private int mStarted;

        @Override
        public void onActivityStarted(Activity activity) {
            if (mStarted == 0) {
                FamilyProfiles.onAppForeground();
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

    /** Saves the error of a crash so the next start can show it (we have no other way to see it). */
    private void installCrashRecorder() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter text = new StringWriter();
                error.printStackTrace(new PrintWriter(text));
                String trace = text.toString();
                if (trace.length() > 3000) {
                    trace = trace.substring(0, 3000);
                }
                FileOutputStream out = new FileOutputStream(new File(getFilesDir(), CRASH_FILE));
                try {
                    out.write(("MyFamilyTube " + BuildConfig.VERSION_NAME + "\n" + trace).getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            } catch (Throwable ignored) {
                // never fail while handling a crash
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /** Returns the saved crash (and forgets it), or null. */
    public static String takeLastCrash(Context context) {
        File file = new File(context.getFilesDir(), CRASH_FILE);
        if (!file.exists()) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] data = new byte[(int) Math.min(file.length(), 8000)];
            int read = in.read(data);
            return read > 0 ? new String(data, 0, read, "UTF-8") : null;
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
