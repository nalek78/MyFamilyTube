package es.myfamilytube.auto;

import android.content.Context;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import com.google.android.gms.cast.framework.media.CastMediaOptions;

import java.util.List;

/**
 * Google Cast: Google's standard player on the Chromecast (Default Media Receiver, no
 * registration needed). The app's own notification and controls are used, not Cast's.
 */
public class CastOptionsProvider implements OptionsProvider {
    @Override
    public CastOptions getCastOptions(Context context) {
        return new CastOptions.Builder()
                .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                .setStopReceiverApplicationWhenEndingSession(true)
                // Media control stays on (it is what plays the songs), but without Cast's own
                // notification and media session: the app has its own
                .setCastMediaOptions(new CastMediaOptions.Builder()
                        .setNotificationOptions(null)
                        .setMediaSessionEnabled(false)
                        .build())
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        return null;
    }
}
