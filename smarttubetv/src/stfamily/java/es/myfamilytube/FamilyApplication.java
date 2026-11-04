package es.myfamilytube;

import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.os.Bundle;

import com.bumptech.glide.Glide;
import com.bumptech.glide.MemoryCategory;
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

    @Override
    public void onCreate() {
        super.onCreate();
        FamilyDiagnostics diagnostics = FamilyDiagnostics.instance(this);
        installCrashRecorder(diagnostics);

        diagnostics.log("Inicio · " + diagnostics.deviceSummary().replace('\n', ' '));
        String problem = diagnostics.checkPreviousExits();
        if (problem != null) {
            diagnostics.setPendingProblem(problem);
        }

        // On TVs with little memory keep fewer pictures in RAM
        if (diagnostics.isLowMemoryDevice()) {
            Glide.get(this).setMemoryCategory(MemoryCategory.LOW);
        }

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

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            FamilyDiagnostics.instance(this).log("Aviso de memoria baja del sistema (nivel " + level + ")");
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Glide.get(this).clearMemory();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        FamilyDiagnostics.instance(this).log("El sistema está casi sin memoria");
        Glide.get(this).clearMemory();
    }

    /** Counts visible screens; going from none to one means the app was opened again. */
    private static final class ForegroundWatcher implements ActivityLifecycleCallbacks {
        private int mStarted;

        @Override
        public void onActivityStarted(Activity activity) {
            if (mStarted == 0) {
                FamilyProfiles.onAppForeground();
                FamilyBrowseActivity.onAppForeground();
            }
            mStarted++;
        }

        @Override
        public void onActivityStopped(Activity activity) {
            mStarted = Math.max(0, mStarted - 1);
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            FamilyDiagnostics.instance(activity).log("Pantalla " + activity.getClass().getSimpleName());
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

    /** Writes Java errors to the diagnostics log (the system record doesn't keep them on older TVs). */
    private void installCrashRecorder(FamilyDiagnostics diagnostics) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                diagnostics.log("ERROR en " + thread.getName() + ": " + compactTrace(error));
                diagnostics.setPendingProblem("error de la app");
            } catch (Throwable ignored) {
                // never fail while handling a crash
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /** The error, its first lines and its root cause, on few lines. */
    private static String compactTrace(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 3) {
            sb.append(depth == 0 ? "" : " | causa: ").append(current);
            StackTraceElement[] stack = current.getStackTrace();
            for (int i = 0; i < Math.min(6, stack.length); i++) {
                sb.append(" | ").append(stack[i]);
            }
            current = current.getCause() != current ? current.getCause() : null;
            depth++;
        }
        return sb.length() > 2500 ? sb.substring(0, 2500) : sb.toString();
    }
}
