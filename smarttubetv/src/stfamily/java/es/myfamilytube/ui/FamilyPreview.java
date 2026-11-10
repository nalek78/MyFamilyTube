package es.myfamilytube.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import android.text.TextUtils;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.video.VideoListener;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import io.reactivex.disposables.Disposable;

import es.myfamilytube.FamilyDiagnostics;

/**
 * Plays the first minute of a video, with sound, inside the focused card's thumbnail.
 * One small player is reused; it starts only when the remote stays on a card for a moment
 * and stops as soon as focus moves, a video is opened or the screen is left.
 */
final class FamilyPreview {
    interface Listener {
        /** Seconds watched in previews (kids' daily time counts them too). */
        void onPreviewWatched(int seconds);
    }

    private static final String PREFS = "family_ui";
    private static final String KEY_ENABLED = "preview_enabled";
    private static final long START_DELAY_MS = 1500;
    private static final long MAX_PLAY_MS = 60_000;

    private final Activity mActivity;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SimpleExoPlayer mPlayer;
    private DefaultTrackSelector mSelector;
    /** The audio track of this preview has been chosen (sound is muted until then). */
    private boolean mAudioChosen;
    private ExoMediaSourceFactory mSourceFactory;
    private Disposable mFormatAction;
    private View mCard;
    private ViewGroup mFrame;
    private TextureView mTexture;
    private Runnable mPendingStart;
    private long mPlayStartMs;

    private final Runnable mTimeUp = this::stop;
    private final Runnable mUnmute = () -> {
        if (mPlayer != null && mTexture != null) {
            mPlayer.setVolume(1f);
        }
    };

    FamilyPreview(Activity activity, Listener listener) {
        mActivity = activity;
        mListener = listener;
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** A card got focus: preview it if the remote stays there. */
    void onCardFocused(View card, Video video) {
        stop();
        if (!isEnabled(mActivity) || video == null || video.isLive || video.videoId == null) {
            return;
        }
        mCard = card;
        mPendingStart = () -> load(card, video);
        mHandler.postDelayed(mPendingStart, START_DELAY_MS);
    }

    /** The card left the screen or lost focus. */
    void onCardGone(View card) {
        if (card == mCard) {
            stop();
        }
    }

    boolean isPlaying() {
        return mTexture != null;
    }

    /** Stops and frees the video decoder (call before opening the real player). */
    void stop() {
        if (mPendingStart != null) {
            mHandler.removeCallbacks(mPendingStart);
            mPendingStart = null;
        }
        mHandler.removeCallbacks(mTimeUp);
        mHandler.removeCallbacks(mUnmute);
        if (mFormatAction != null) {
            mFormatAction.dispose();
            mFormatAction = null;
        }
        if (mPlayStartMs > 0) {
            int seconds = (int) ((System.currentTimeMillis() - mPlayStartMs) / 1000);
            mPlayStartMs = 0;
            if (seconds > 0) {
                mListener.onPreviewWatched(seconds);
            }
        }
        if (mPlayer != null) {
            mPlayer.stop(true);
            mPlayer.clearVideoTextureView(mTexture);
        }
        if (mTexture != null && mFrame != null) {
            mFrame.removeView(mTexture);
        }
        mTexture = null;
        mFrame = null;
        mCard = null;
    }

    /** Screen closed or paused: give everything back. */
    void release() {
        stop();
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
        if (mSourceFactory != null) {
            mSourceFactory.release();
            mSourceFactory = null;
        }
    }

    private void load(View card, Video video) {
        mPendingStart = null;
        mFormatAction = YouTubeServiceManager.instance().getMediaItemService()
                .getFormatInfoObserve(video.videoId)
                .subscribe(
                        info -> mActivity.runOnUiThread(() -> play(card, info)),
                        error -> mActivity.runOnUiThread(() ->
                                FamilyDiagnostics.instance(mActivity).log("Vista previa sin datos: " + error.getMessage())));
    }

    private void play(View card, MediaItemFormatInfo info) {
        mFormatAction = null;
        if (card != mCard || !card.hasFocus() || mActivity.isFinishing() || info == null || info.isUnplayable()) {
            return;
        }
        MediaSource source = buildSource(info);
        if (source == null) {
            return;
        }
        ViewGroup frame = card.findViewById(R.id.family_card_thumb_frame);
        if (frame == null) {
            return;
        }
        ensurePlayer();

        TextureView texture = new TextureView(mActivity);
        texture.setAlpha(0f); // the thumbnail stays visible until the first frame arrives
        // Above the picture, below the duration badge
        frame.addView(texture, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mFrame = frame;
        mTexture = texture;

        mPlayer.setVideoTextureView(texture);
        // Silent until the right audio track is picked (videos may carry several dubbed languages)
        mAudioChosen = false;
        mSelector.setParameters(mSelector.buildUponParameters().clearSelectionOverrides());
        mPlayer.setVolume(0f);
        mPlayer.prepare(source);
        mPlayer.setPlayWhenReady(true);
        mPlayStartMs = System.currentTimeMillis();
        mHandler.postDelayed(mTimeUp, MAX_PLAY_MS);
    }

    private MediaSource buildSource(MediaItemFormatInfo info) {
        try {
            if (mSourceFactory == null) {
                mSourceFactory = new ExoMediaSourceFactory(mActivity);
            }
            if (info.containsDashFormats()) {
                return mSourceFactory.fromDashFormatInfo(info);
            }
            if (info.containsSabrFormats()) {
                return mSourceFactory.fromSabrFormatInfo(info);
            }
            if (info.containsDashUrl()) {
                return mSourceFactory.fromDashManifestUrl(info.getDashManifestUrl());
            }
            if (info.containsUrlFormats()) {
                return mSourceFactory.fromUrlList(info.createUrlList());
            }
        } catch (Throwable e) {
            FamilyDiagnostics.instance(mActivity).log("Vista previa: no se pudo preparar el vídeo: " + e);
        }
        return null;
    }

    /**
     * Same rule as the real player: the audio language chosen in the player's settings, if any;
     * otherwise the video's original track (YouTube adds automatic dubs, e.g. Portuguese).
     */
    /** @return true when a different track was requested (the switch happens a moment later) */
    private boolean chooseAudioTrack(TrackSelectionArray selections) {
        MappingTrackSelector.MappedTrackInfo info = mSelector.getCurrentMappedTrackInfo();
        if (info == null) {
            return false;
        }
        String saved = PlayerData.instance(mActivity).getAudioLanguage();
        if ("in".equals(saved)) {
            saved = "id"; // same fix as the real player
        }
        for (int renderer = 0; renderer < info.getRendererCount(); renderer++) {
            if (info.getRendererType(renderer) != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            TrackGroupArray groups = info.getTrackGroups(renderer);
            int[] wanted = TextUtils.isEmpty(saved) ? null : findAudio(groups, saved, false);
            if (wanted == null) {
                wanted = findAudio(groups, "original", true);
            }
            if (wanted == null || isSelected(selections, renderer, groups.get(wanted[0]).getFormat(wanted[1]))) {
                return false; // nothing to change
            }
            mSelector.setParameters(mSelector.buildUponParameters().setSelectionOverride(renderer, groups,
                    new DefaultTrackSelector.SelectionOverride(wanted[0], wanted[1])));
            return true;
        }
        return false;
    }

    /** Whether the current selection already uses an audio track in that language. */
    private static boolean isSelected(TrackSelectionArray selections, int renderer, Format wanted) {
        if (selections == null || renderer >= selections.length || selections.get(renderer) == null) {
            return false;
        }
        Format current = selections.get(renderer).getSelectedFormat();
        return current != null && current.language != null && current.language.equals(wanted.language);
    }

    /** @return {group, track} of the lowest-bitrate audio whose language matches, or null */
    private static int[] findAudio(TrackGroupArray groups, String language, boolean contains) {
        int[] best = null;
        int bestBitrate = Integer.MAX_VALUE;
        for (int g = 0; g < groups.length; g++) {
            TrackGroup group = groups.get(g);
            for (int t = 0; t < group.length; t++) {
                Format format = group.getFormat(t);
                String lang = format.language;
                if (lang == null) {
                    continue;
                }
                boolean match = contains ? lang.contains(language) : lang.startsWith(language);
                int bitrate = format.bitrate > 0 ? format.bitrate : Integer.MAX_VALUE - 1;
                if (match && bitrate < bestBitrate) {
                    best = new int[]{g, t};
                    bestBitrate = bitrate;
                }
            }
        }
        return best;
    }

    private void ensurePlayer() {
        if (mPlayer != null) {
            return;
        }
        // Low quality and a small buffer: enough for a thumbnail, light on a modest TV
        DefaultTrackSelector selector = new DefaultTrackSelector();
        mSelector = selector;
        selector.setParameters(selector.buildUponParameters()
                .setMaxVideoSize(854, 480)
                .setMaxVideoBitrate(1_500_000));
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_500, 15_000, 1_000, 1_500)
                .createDefaultLoadControl();
        mPlayer = ExoPlayerFactory.newSimpleInstance(mActivity, selector, loadControl);
        mPlayer.addVideoListener(new VideoListener() {
            @Override
            public void onRenderedFirstFrame() {
                if (mTexture != null) {
                    mTexture.animate().alpha(1f).setDuration(200).start();
                }
            }
        });
        mPlayer.addListener(new Player.EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    stop();
                }
            }

            @Override
            public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
                if (mPlayer == null) {
                    return;
                }
                if (!mAudioChosen) {
                    mAudioChosen = true;
                    if (chooseAudioTrack(trackSelections)) {
                        // Switching: unmute when the new selection arrives (or shortly after, just in case)
                        mHandler.postDelayed(mUnmute, 1500);
                        return;
                    }
                }
                mHandler.removeCallbacks(mUnmute);
                mUnmute.run();
            }

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                FamilyDiagnostics.instance(mActivity).log("Vista previa: error al reproducir: " + error.getMessage());
                stop();
            }
        });
    }
}
