package es.myfamilytube.auto;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.ChatReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.SeekBarSegment;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.ExoPlayerInitializer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer.CustomOverridesRenderersFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.RestoreTrackSelector;

import java.io.InputStream;
import java.util.List;

/**
 * The same YouTube engine as the phone player, without a screen: for Android Auto.
 * Only the sound is downloaded (the video track is turned off).
 */
final class CarPlayer implements PlaybackView, BasePresenter.Headless {
    interface Listener {
        void onStateChanged();

        /** The engine wants to close the player (end of a list, content not allowed…). */
        void onFinishRequested();
    }

    private final Context mContext;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final PlaybackPresenter mPresenter;
    private ExoPlayerController mController;
    private ExoPlayerInitializer mInitializer;
    private SimpleExoPlayer mPlayer;
    private boolean mEngineBlocked;
    private int mResizeMode;
    private float mSpeed = 1.0f;

    private final Player.EventListener mStateListener = new Player.EventListener() {
        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            mListener.onStateChanged();
        }
    };

    CarPlayer(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
        mPresenter = PlaybackPresenter.instance(mContext);
    }

    /** Takes the engine (the phone player, if open, gives it up) and plays the video. */
    void play(Video video) {
        ensureEngine();
        mPresenter.setView(this);
        mPresenter.onNewVideo(video);
    }

    boolean isActive() {
        return mPlayer != null && mPresenter.getView() == this;
    }

    private void ensureEngine() {
        if (mPlayer != null) {
            return;
        }
        mInitializer = new ExoPlayerInitializer(mContext);
        mController = new ExoPlayerController(mContext, mPresenter);
        DefaultTrackSelector trackSelector = new RestoreTrackSelector(new AdaptiveTrackSelection.Factory());
        mController.setTrackSelector(trackSelector);
        DefaultRenderersFactory renderersFactory = new CustomOverridesRenderersFactory(mContext);
        // The whole song is downloaded as fast as possible (sound only: about 1 MB a minute)
        mInitializer.setLoadControl(es.myfamilytube.ui.MobileBuffers.song());
        mPlayer = mInitializer.createPlayer(mContext, renderersFactory, trackSelector);
        // Music in the car: ask for the audio focus so the radio or other apps step aside
        mPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .build(), true);
        mController.setPlayer(mPlayer);
        mController.setPlayerView(new com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.PlayerView() {
            @Override
            public void setQualityInfo(String info) {
            }

            @Override
            public void setVideo(Video video) {
                mListener.onStateChanged();
            }
        });
        // Only the sound: much less data
        mController.selectFormat(FormatItem.NO_VIDEO);
        mPlayer.addListener(mStateListener);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
        mPresenter.onEngineInitialized();
    }

    void release() {
        if (mPlayer == null) {
            return;
        }
        if (mPresenter.getView() == this) {
            mPresenter.onEngineReleased();
        }
        mPlayer.removeListener(mStateListener);
        mInitializer.release();
        mController.release();
        mPlayer = null;
        mController = null;
        mHandler.removeCallbacksAndMessages(null);
    }

    SimpleExoPlayer getExoPlayer() {
        return mPlayer;
    }

    int getPlaybackState() {
        return mPlayer != null ? mPlayer.getPlaybackState() : Player.STATE_IDLE;
    }

    long getBufferedPositionMs() {
        return mPlayer != null ? mPlayer.getBufferedPosition() : 0;
    }

    void next() {
        if (isActive()) {
            mPresenter.onNextClicked();
        }
    }

    void previous() {
        if (isActive()) {
            mPresenter.onPreviousClicked();
        }
    }

    // ---------------------------------------------------------------- PlayerManager

    @Override
    public void setVideo(Video video) {
        if (mController != null) {
            mController.setVideo(video);
        }
        mListener.onStateChanged();
    }

    @Override
    public Video getVideo() {
        return mController != null ? mController.getVideo() : null;
    }

    @Override
    public void finish() {
        mHandler.post(mListener::onFinishRequested);
    }

    @Override
    public void finishReally() {
        finish();
    }

    @Override
    public void showBackground(String url) {
    }

    @Override
    public void showBackgroundColor(int colorResId) {
    }

    @Override
    public void resetPlayerState() {
        if (mController != null) {
            mController.resetPlayerState();
        }
    }

    @Override
    public boolean isEmbed() {
        return false;
    }

    // ---------------------------------------------------------------- PlayerEngine

    @Override
    public void openSabr(MediaItemFormatInfo formatInfo) {
        if (mController != null) {
            mController.openSabr(formatInfo);
        }
    }

    @Override
    public void openDash(MediaItemFormatInfo formatInfo) {
        if (mController != null) {
            mController.openDash(formatInfo);
        }
    }

    @Override
    public void openDash(InputStream dashManifest) {
        if (mController != null) {
            mController.openDash(dashManifest);
        }
    }

    @Override
    public void openDashUrl(String dashManifestUrl) {
        if (mController != null) {
            mController.openDashUrl(dashManifestUrl);
        }
    }

    @Override
    public void openHlsUrl(String hlsPlaylistUrl) {
        if (mController != null) {
            mController.openHlsUrl(hlsPlaylistUrl);
        }
    }

    @Override
    public void openUrlList(List<String> urlList) {
        if (mController != null) {
            mController.openUrlList(urlList);
        }
    }

    @Override
    public void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
        if (mController != null) {
            mController.openMerged(formatInfo, hlsPlaylistUrl);
        }
    }

    @Override
    public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        if (mController != null) {
            mController.openMerged(dashManifest, hlsPlaylistUrl);
        }
    }

    @Override
    public long getPositionMs() {
        return mController != null ? mController.getPositionMs() : 0;
    }

    @Override
    public void setPositionMs(long positionMs) {
        if (mController != null) {
            mController.setPositionMs(positionMs);
        }
    }

    @Override
    public long getDurationMs() {
        return mController != null ? mController.getDurationMs() : 0;
    }

    @Override
    public void setPlayWhenReady(boolean play) {
        if (mController != null) {
            mController.setPlayWhenReady(play);
        }
        mListener.onStateChanged();
    }

    @Override
    public boolean getPlayWhenReady() {
        return mController != null && mController.getPlayWhenReady();
    }

    @Override
    public boolean isPlaying() {
        return mController != null && mController.isPlaying();
    }

    @Override
    public boolean isLoading() {
        return mController != null && mController.isLoading();
    }

    @Override
    public List<FormatItem> getVideoFormats() {
        return mController != null ? mController.getVideoFormats() : null;
    }

    @Override
    public List<FormatItem> getAudioFormats() {
        return mController != null ? mController.getAudioFormats() : null;
    }

    @Override
    public List<FormatItem> getSubtitleFormats() {
        return mController != null ? mController.getSubtitleFormats() : null;
    }

    @Override
    public void setFormat(FormatItem option) {
        if (mController == null || option == null) {
            return;
        }
        // The engine restores the saved video quality: in the car the picture stays off
        mController.selectFormat(option.getType() == FormatItem.TYPE_VIDEO ? FormatItem.NO_VIDEO : option);
    }

    @Override
    public FormatItem getVideoFormat() {
        return mController != null ? mController.getVideoFormat() : null;
    }

    @Override
    public FormatItem getAudioFormat() {
        return mController != null ? mController.getAudioFormat() : null;
    }

    @Override
    public FormatItem getSubtitleFormat() {
        return mController != null ? mController.getSubtitleFormat() : null;
    }

    @Override
    public boolean isEngineInitialized() {
        return mPlayer != null;
    }

    @Override
    public void restartEngine() {
        Video video = getVideo();
        release();
        if (video != null) {
            play(video);
        }
    }

    @Override
    public void reloadPlayback() {
        if (mPlayer != null) {
            mPresenter.onEngineReleased();
            mPresenter.onEngineInitialized();
        }
    }

    @Override
    public void blockEngine(boolean block) {
        mEngineBlocked = block;
    }

    @Override
    public boolean isEngineBlocked() {
        return mEngineBlocked;
    }

    @Override
    public boolean isInPIPMode() {
        return false;
    }

    @Override
    public boolean containsMedia() {
        return mController != null && mController.containsMedia();
    }

    @Override
    public void setSpeed(float speed) {
        // Music in the car always at normal speed
        mSpeed = 1.0f;
        if (mController != null) {
            mController.setSpeed(1.0f);
        }
    }

    @Override
    public float getSpeed() {
        return mSpeed;
    }

    @Override
    public void setPitch(float pitch) {
        if (mController != null) {
            mController.setPitch(pitch);
        }
    }

    @Override
    public float getPitch() {
        return mController != null ? mController.getPitch() : 1.0f;
    }

    @Override
    public void setVolume(float volume) {
        if (mController != null) {
            mController.setVolume(volume);
        }
    }

    @Override
    public float getVolume() {
        return mController != null ? mController.getVolume() : 1.0f;
    }

    @Override
    public void setResizeMode(int mode) {
        mResizeMode = mode;
    }

    @Override
    public int getResizeMode() {
        return mResizeMode;
    }

    @Override
    public void setZoomPercents(int percents) {
    }

    @Override
    public void setAspectRatio(float ratio) {
    }

    @Override
    public void setRotationAngle(int angle) {
    }

    @Override
    public void setVideoFlipEnabled(boolean enabled) {
    }

    @Override
    public void setVideoGravity(int gravity) {
    }

    // ---------------------------------------------------------------- PlayerUI (no screen)

    @Override
    public void updateSuggestions(VideoGroup group) {
    }

    @Override
    public void removeSuggestions(VideoGroup group) {
    }

    @Override
    public int getSuggestionsIndex(VideoGroup group) {
        return -1;
    }

    @Override
    public VideoGroup getSuggestionsByIndex(int index) {
        return null;
    }

    @Override
    public void focusSuggestedItem(int index) {
    }

    @Override
    public void focusSuggestedItem(Video video) {
    }

    @Override
    public void resetSuggestedPosition() {
    }

    @Override
    public boolean isSuggestionsEmpty() {
        return true;
    }

    @Override
    public void clearSuggestions() {
    }

    @Override
    public void showOverlay(boolean show) {
    }

    @Override
    public boolean isOverlayShown() {
        return false;
    }

    @Override
    public void showSuggestions(boolean show) {
    }

    @Override
    public boolean isSuggestionsShown() {
        return false;
    }

    @Override
    public void showControls(boolean show) {
    }

    @Override
    public boolean isControlsShown() {
        return false;
    }

    @Override
    public int getButtonState(int buttonId) {
        return BUTTON_OFF;
    }

    @Override
    public void setButtonState(int buttonId, int buttonState) {
    }

    @Override
    public void setChannelIcon(String iconUrl) {
    }

    @Override
    public void setSeekPreviewTitle(String title) {
    }

    @Override
    public void setNextTitle(Video nextVideo) {
    }

    @Override
    public void showDebugInfo(boolean show) {
    }

    @Override
    public void showSubtitles(boolean show) {
    }

    @Override
    public void loadStoryboard() {
    }

    @Override
    public void setTitle(String title) {
    }

    @Override
    public void showProgressBar(boolean show) {
    }

    @Override
    public void setSeekBarSegments(List<SeekBarSegment> segments) {
    }

    @Override
    public void updateEndingTime() {
    }

    @Override
    public void setChatReceiver(ChatReceiver chatReceiver) {
    }
}
