package es.myfamilytube.auto;

import android.content.Context;
import android.net.Uri;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaSeekOptions;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;

/**
 * The connection with the Chromecast (Google Cast): knows when the phone starts or stops sending,
 * plays the songs there and tells how they are going.
 */
final class CastController {
    interface Listener {
        void onCastStarted(String deviceName);

        /** Stopped sending: where the song was, to go on on the phone. */
        void onCastEnded(long positionMs);

        void onRemoteState();

        /** The song finished on the Chromecast (or couldn't be played: error). */
        void onRemoteFinished(boolean error);

        /** Something worth writing in «Diagnóstico». */
        void onNote(String text);
    }

    private final CastContext mContext;
    private final Listener mListener;
    private CastSession mSession;
    private RemoteMediaClient mClient;
    private boolean mWantPlay;
    private boolean mStarted;
    private boolean mFinishedSent = true;
    private long mLastPosition;

    private CastController(CastContext context, Listener listener) {
        mContext = context;
        mListener = listener;
        mContext.getSessionManager().addSessionManagerListener(mSessionListener, CastSession.class);
        CastSession current = mContext.getSessionManager().getCurrentCastSession();
        if (current != null && current.isConnected()) {
            attach(current);
        }
    }

    /** null if this phone can't send (no Google Play services). */
    static CastController create(Context context, Listener listener) {
        try {
            return new CastController(CastContext.getSharedInstance(context.getApplicationContext()), listener);
        } catch (Throwable e) {
            return null;
        }
    }

    void release() {
        mContext.getSessionManager().removeSessionManagerListener(mSessionListener, CastSession.class);
        detach();
    }

    boolean isCasting() {
        return mClient != null;
    }

    String deviceName() {
        try {
            return mSession != null && mSession.getCastDevice() != null ? mSession.getCastDevice().getFriendlyName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    void endSession() {
        mContext.getSessionManager().endCurrentSession(true);
    }

    // ---------------------------------------------------------------- the song on the Chromecast

    void load(String url, String mimeType, String title, String artist, String album, String imageUrl, long startMs, boolean play) {
        if (mClient == null) {
            return;
        }
        MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
        if (title != null) {
            metadata.putString(MediaMetadata.KEY_TITLE, title);
        }
        if (artist != null) {
            metadata.putString(MediaMetadata.KEY_ARTIST, artist);
        }
        if (album != null) {
            metadata.putString(MediaMetadata.KEY_ALBUM_TITLE, album);
        }
        if (imageUrl != null) {
            metadata.addImage(new WebImage(Uri.parse(imageUrl)));
        }
        MediaInfo info = new MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mimeType)
                .setMetadata(metadata)
                .build();
        mWantPlay = play;
        mStarted = false;
        mFinishedSent = false;
        mClient.load(new MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(play)
                .setCurrentTime(Math.max(0, startMs))
                .build())
                .setResultCallback(result -> {
                    if (result != null && result.getStatus() != null && !result.getStatus().isSuccess()) {
                        mListener.onNote("Cast: el aparato rechazó la canción · código " + result.getStatus().getStatusCode()
                                + (result.getStatus().getStatusMessage() != null ? " · " + result.getStatus().getStatusMessage() : ""));
                    }
                });
    }

    void play() {
        mWantPlay = true;
        if (mClient != null) {
            mClient.play();
        }
    }

    void pause() {
        mWantPlay = false;
        if (mClient != null) {
            mClient.pause();
        }
    }

    void seek(long positionMs) {
        if (mClient != null) {
            mClient.seek(new MediaSeekOptions.Builder().setPosition(positionMs).build());
        }
    }

    long position() {
        if (mClient == null) {
            return mLastPosition;
        }
        mLastPosition = mClient.getApproximateStreamPosition();
        return mLastPosition;
    }

    long duration() {
        return mClient != null ? Math.max(0, mClient.getStreamDuration()) : 0;
    }

    boolean wantsToPlay() {
        return mWantPlay;
    }

    boolean isPlaying() {
        return mClient != null && mClient.getPlayerState() == MediaStatus.PLAYER_STATE_PLAYING;
    }

    boolean isBuffering() {
        if (mClient == null) {
            return false;
        }
        int state = mClient.getPlayerState();
        return state == MediaStatus.PLAYER_STATE_BUFFERING || state == MediaStatus.PLAYER_STATE_LOADING
                || (state == MediaStatus.PLAYER_STATE_IDLE && !mFinishedSent && mWantPlay);
    }

    double volume() {
        try {
            return mSession != null ? mSession.getVolume() : 0.5;
        } catch (Exception e) {
            return 0.5;
        }
    }

    void setVolume(double volume) {
        try {
            if (mSession != null) {
                mSession.setVolume(Math.max(0, Math.min(1, volume)));
            }
        } catch (Exception e) {
            // not now
        }
    }

    // ---------------------------------------------------------------- connection

    private void attach(CastSession session) {
        mSession = session;
        mClient = session.getRemoteMediaClient();
        if (mClient != null) {
            mClient.registerCallback(mClientCallback);
        }
        mListener.onNote("Cast: conectado con " + deviceName() + (mClient != null ? "" : " · SIN control de medios"));
    }

    private void detach() {
        if (mClient != null) {
            mLastPosition = mClient.getApproximateStreamPosition();
            mClient.unregisterCallback(mClientCallback);
        }
        mClient = null;
        mSession = null;
    }

    private final RemoteMediaClient.Callback mClientCallback = new RemoteMediaClient.Callback() {
        @Override
        public void onStatusUpdated() {
            RemoteMediaClient client = mClient;
            if (client == null) {
                return;
            }
            int state = client.getPlayerState();
            if (state == MediaStatus.PLAYER_STATE_PLAYING) {
                mStarted = true;
                mWantPlay = true;
            } else if (state == MediaStatus.PLAYER_STATE_PAUSED) {
                mWantPlay = false; // also when paused from the TV's remote
            } else if (state == MediaStatus.PLAYER_STATE_IDLE && !mFinishedSent) {
                int reason = client.getIdleReason();
                if (reason == MediaStatus.IDLE_REASON_FINISHED && mStarted) {
                    mFinishedSent = true;
                    mListener.onRemoteFinished(false);
                    return;
                }
                if (reason == MediaStatus.IDLE_REASON_ERROR) {
                    mListener.onNote("Cast: el aparato no pudo reproducir la canción (error al cargarla o formato)");
                    mFinishedSent = true;
                    mListener.onRemoteFinished(true);
                    return;
                }
            }
            mListener.onRemoteState();
        }
    };

    private final SessionManagerListener<CastSession> mSessionListener = new SessionManagerListener<CastSession>() {
        private void started(CastSession session) {
            attach(session);
            mListener.onCastStarted(deviceName());
        }

        private void ended() {
            boolean was = mClient != null;
            long position = mClient != null ? mClient.getApproximateStreamPosition() : mLastPosition;
            detach();
            if (was) {
                mListener.onCastEnded(position);
            }
        }

        @Override
        public void onSessionStarted(CastSession session, String sessionId) {
            started(session);
        }

        @Override
        public void onSessionResumed(CastSession session, boolean wasSuspended) {
            started(session);
        }

        @Override
        public void onSessionEnding(CastSession session) {
            if (mClient != null) {
                mLastPosition = mClient.getApproximateStreamPosition();
            }
        }

        @Override
        public void onSessionEnded(CastSession session, int error) {
            ended();
        }

        @Override
        public void onSessionSuspended(CastSession session, int reason) {
            ended();
        }

        @Override
        public void onSessionStarting(CastSession session) {
        }

        @Override
        public void onSessionStartFailed(CastSession session, int error) {
            mListener.onNote("Cast: no se pudo conectar con el aparato · código " + error);
        }

        @Override
        public void onSessionResuming(CastSession session, String sessionId) {
        }

        @Override
        public void onSessionResumeFailed(CastSession session, int error) {
        }
    };
}
