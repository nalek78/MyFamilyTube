package es.myfamilytube.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.session.MediaSessionCompat;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Keeps the app alive while a video plays outside the player screen (floating window or screen off)
 * and shows the notification with its controls (also on the lock screen).
 */
public class MobilePlaybackService extends Service {
    /** What the notification buttons act on: the open player. */
    interface Controls {
        void onNotificationPlayPause();

        void onNotificationNext();

        void onNotificationPrevious();

        void onNotificationClose();

        boolean isNotificationPlaying();

        String getNotificationTitle();

        String getNotificationText();

        String getNotificationImage();

        MediaSessionCompat.Token getSessionToken();
    }

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 4802;
    private static final String ACTION_UPDATE = "es.myfamilytube.UPDATE";
    private static final String ACTION_PLAY_PAUSE = "es.myfamilytube.PLAY_PAUSE";
    private static final String ACTION_NEXT = "es.myfamilytube.NEXT";
    private static final String ACTION_PREVIOUS = "es.myfamilytube.PREVIOUS";
    private static final String ACTION_CLOSE = "es.myfamilytube.CLOSE";

    private static Controls sControls;
    private static boolean sRunning;

    private android.os.PowerManager.WakeLock mWakeLock;
    private android.net.wifi.WifiManager.WifiLock mWifiLock;
    private Bitmap mImage;
    private String mImageUrl;

    /** Starts (or refreshes) the notification. Call it while the player is still on screen. */
    static void start(Context context, Controls controls) {
        sControls = controls;
        Intent intent = new Intent(context, MobilePlaybackService.class).setAction(ACTION_UPDATE);
        try {
            if (!sRunning && Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
            // Android refuses to start it from the background: playback just stops with the screen
        }
    }

    /** Redraws the notification (play/pause state, new video). */
    static void update(Context context) {
        if (sRunning) {
            try {
                context.startService(new Intent(context, MobilePlaybackService.class).setAction(ACTION_UPDATE));
            } catch (RuntimeException e) {
                // nothing to update
            }
        }
    }

    static void stop(Context context, Controls controls) {
        if (sControls == controls) {
            sControls = null;
        }
        if (sRunning) {
            context.stopService(new Intent(context, MobilePlaybackService.class));
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            mWakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MyFamilyTube:playback");
            mWakeLock.setReferenceCounted(false);
        }
        android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            mWifiLock = wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MyFamilyTube:playback");
            mWifiLock.setReferenceCounted(false);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always go foreground first: Android closes the app if a started service doesn't
        showNotification();
        Controls controls = sControls;
        String action = intent != null ? intent.getAction() : null;
        if (controls == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY_PAUSE.equals(action)) {
            controls.onNotificationPlayPause();
        } else if (ACTION_NEXT.equals(action)) {
            controls.onNotificationNext();
        } else if (ACTION_PREVIOUS.equals(action)) {
            controls.onNotificationPrevious();
        } else if (ACTION_CLOSE.equals(action)) {
            controls.onNotificationClose();
            return START_NOT_STICKY;
        }
        if (!ACTION_UPDATE.equals(action)) {
            showNotification();
        }
        return START_NOT_STICKY;
    }

    /** CPU and wifi stay awake only while something is playing (screen off). */
    private void holdLocks(boolean hold) {
        try {
            if (mWakeLock != null) {
                if (hold && !mWakeLock.isHeld()) {
                    mWakeLock.acquire(4 * 60 * 60 * 1000L);
                } else if (!hold && mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
            }
            if (mWifiLock != null) {
                if (hold && !mWifiLock.isHeld()) {
                    mWifiLock.acquire();
                } else if (!hold && mWifiLock.isHeld()) {
                    mWifiLock.release();
                }
            }
        } catch (RuntimeException e) {
            // not essential
        }
    }

    @Override
    public void onDestroy() {
        holdLocks(false);
        sRunning = false;
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiped away from recent apps: stop the sound too
        Controls controls = sControls;
        if (controls != null) {
            controls.onNotificationClose();
        }
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    private PendingIntent action(String action, int code) {
        Intent intent = new Intent(this, MobilePlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, code, intent, flags);
    }

    private void showNotification() {
        Controls controls = sControls;
        boolean playing = controls != null && controls.isNotificationPlaying();
        holdLocks(playing);
        String title = controls != null ? controls.getNotificationTitle() : null;
        String text = controls != null ? controls.getNotificationText() : null;

        Intent open = new Intent(this, MobilePlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.mobile_ic_play)
                .setContentTitle(!TextUtils.isEmpty(title) ? title : getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(content)
                .setDeleteIntent(action(ACTION_CLOSE, 5))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .setColor(0xFFE0261E)
                .addAction(R.drawable.mobile_ic_prev, "Anterior", action(ACTION_PREVIOUS, 1))
                .addAction(playing ? R.drawable.mobile_ic_pause : R.drawable.mobile_ic_play,
                        playing ? "Pausa" : "Reproducir", action(ACTION_PLAY_PAUSE, 2))
                .addAction(R.drawable.mobile_ic_next, "Siguiente", action(ACTION_NEXT, 3))
                .addAction(R.drawable.mobile_ic_back, "Cerrar", action(ACTION_CLOSE, 4));

        androidx.media.app.NotificationCompat.MediaStyle style = new androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2);
        MediaSessionCompat.Token token = controls != null ? controls.getSessionToken() : null;
        if (token != null) {
            style.setMediaSession(token);
        }
        builder.setStyle(style);

        loadImage(controls != null ? controls.getNotificationImage() : null);
        if (mImage != null) {
            builder.setLargeIcon(mImage);
        }

        Notification notification = builder.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            stopSelf();
        }
    }

    /** The video's picture for the notification and the lock screen (loaded once per video). */
    private void loadImage(String url) {
        if (TextUtils.isEmpty(url) || url.equals(mImageUrl)) {
            return;
        }
        mImageUrl = url;
        mImage = null;
        Glide.with(getApplicationContext())
                .asBitmap()
                .load(url)
                .into(new CustomTarget<Bitmap>(320, 180) {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        if (url.equals(mImageUrl) && sRunning) {
                            mImage = resource;
                            showNotification();
                        }
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                    }
                });
    }
}
