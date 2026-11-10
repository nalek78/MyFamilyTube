package es.myfamilytube.ui;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.SparseIntArray;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.ChatReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.SeekBarSegment;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.ExoPlayerInitializer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer.CustomOverridesRenderersFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.RestoreTrackSelector;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import es.myfamilytube.profiles.FamilyProfiles;

/**
 * Touch player for phones. Same engine as the TV (formats, languages, history, parental control),
 * with its own screen: video on top with tap/double-tap controls, details and suggestions below,
 * and the whole screen when the phone is turned sideways.
 */
public class MobilePlayerActivity extends MotherActivity implements PlaybackView, MobileFeedAdapter.Listener {
    private static final long HIDE_CONTROLS_MS = 3500;
    private static final long JUMP_MS = 10_000;
    private static final int COLOR_ON = 0xFFFF3D33;
    private static final int COLOR_OFF = 0xFFF2F2F2;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private PlaybackPresenter mPresenter;
    private ExoPlayerController mController;
    private ExoPlayerInitializer mInitializer;
    private SimpleExoPlayer mPlayer;
    private boolean mEngineBlocked;
    private boolean mFinished;

    private View mVideoFrame;
    private com.google.android.exoplayer2.ui.PlayerView mPlayerView;
    private View mControls;
    private ImageView mPlayButton;
    private ImageView mFullscreenButton;
    private SeekBar mSeek;
    private TextView mTime;
    private TextView mDuration;
    private TextView mSeekHint;
    private ProgressBar mBuffering;
    private TextView mOverlayTitle;
    private View mDetails;
    private TextView mTitle;
    private TextView mMeta;
    private TextView mChannel;
    private LinearLayout mActions;
    private RecyclerView mList;

    private final MobileFeed mFeed = new MobileFeed();
    private final List<VideoGroup> mGroups = new ArrayList<>();
    private MobileFeedAdapter mAdapter;
    private Video mLastScrollEnd;

    private final SparseIntArray mButtonStates = new SparseIntArray();
    private final SparseIntArray mButtonViews = new SparseIntArray(); // button id -> child index
    private boolean mSeeking;
    private int mResizeMode;
    private OrientationEventListener mOrientationListener;
    /** After the fullscreen button forced an orientation: give it back to the sensor once the phone matches it. */
    private int mForcedOrientation = -1;

    private final Runnable mHideControls = () -> showControls(false);
    private final Runnable mProgressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            mHandler.postDelayed(this, 500);
        }
    };

    private final Player.EventListener mStateListener = new Player.EventListener() {
        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            updatePlayButton();
            mBuffering.setVisibility(playbackState == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            mVideoFrame.setKeepScreenOn(playWhenReady && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE);
        }
    };

    /** Passes quality changes and the current video from the engine to the screen. */
    private final com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.PlayerView mEngineView =
            new com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.PlayerView() {
                @Override
                public void setQualityInfo(String info) {
                    // Shown in the quality dialog, nothing to draw here
                }

                @Override
                public void setVideo(Video video) {
                    showVideoInfo(video);
                }
            };

    @Override
    protected void initTheme() {
        setTheme(R.style.MobilePlayerTheme);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);
        setContentView(R.layout.mobile_player);

        mVideoFrame = findViewById(R.id.mobile_video_frame);
        mPlayerView = findViewById(R.id.mobile_player_view);
        mControls = findViewById(R.id.mobile_controls);
        mPlayButton = findViewById(R.id.mobile_player_play);
        mFullscreenButton = findViewById(R.id.mobile_player_fullscreen);
        mSeek = findViewById(R.id.mobile_player_seek);
        mTime = findViewById(R.id.mobile_player_time);
        mDuration = findViewById(R.id.mobile_player_duration);
        mSeekHint = findViewById(R.id.mobile_seek_hint);
        mBuffering = findViewById(R.id.mobile_player_buffering);
        mOverlayTitle = findViewById(R.id.mobile_player_overlay_title);
        mDetails = findViewById(R.id.mobile_player_details);
        mTitle = findViewById(R.id.mobile_player_title);
        mMeta = findViewById(R.id.mobile_player_meta);
        mChannel = findViewById(R.id.mobile_player_channel);
        mActions = findViewById(R.id.mobile_player_actions);
        mList = findViewById(R.id.mobile_player_list);

        mFeed.setShowTitles(false);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new MobileFeedAdapter(this, true);
        mList.setAdapter(mAdapter);

        setupControls();
        setupGestures();
        buildActions();
        applyOrientation(getResources().getConfiguration().orientation);

        mInitializer = new ExoPlayerInitializer(this);
        mPresenter = PlaybackPresenter.instance(this);
        mPresenter.setView(this);
        mController = new ExoPlayerController(this, mPresenter);
        mPresenter.onViewInitialized();

        mOrientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int degrees) {
                releaseForcedOrientation(degrees);
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        initializePlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPlayer == null) {
            initializePlayer();
        }
        mPresenter.setView(this);
        mPresenter.onViewResumed();
        mEngineBlocked = false;
        mHandler.post(mProgressTick);
        if (mOrientationListener.canDetectOrientation()) {
            mOrientationListener.enable();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPresenter.onViewPaused();
        mHandler.removeCallbacks(mProgressTick);
        mOrientationListener.disable();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!mEngineBlocked) {
            releasePlayer();
        }
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (isLandscape()) {
            setFullscreen(false);
            return;
        }
        finish();
    }

    @Override
    public void finish() {
        if (!mFinished) {
            mFinished = true;
            releasePlayer();
            mPresenter.onFinish();
        }
        super.finish();
    }

    @Override
    public void finishReally() {
        finish();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyOrientation(newConfig.orientation);
    }

    // ---------------------------------------------------------------- engine life

    private void initializePlayer() {
        if (mPlayer != null) {
            return;
        }
        DefaultTrackSelector trackSelector = new RestoreTrackSelector(new AdaptiveTrackSelection.Factory());
        mController.setTrackSelector(trackSelector);
        DefaultRenderersFactory renderersFactory = new CustomOverridesRenderersFactory(this);
        mPlayer = mInitializer.createPlayer(this, renderersFactory, trackSelector);
        mController.setPlayer(mPlayer);
        mController.setPlayerView(mEngineView);
        mPlayer.addListener(mStateListener);
        mPlayerView.setPlayer(mPlayer);

        mPresenter.setView(this);
        mPresenter.onEngineInitialized();
    }

    private void releasePlayer() {
        if (mPlayer == null) {
            return;
        }
        mPresenter.onEngineReleased();
        mPlayer.removeListener(mStateListener);
        mPlayerView.setPlayer(null);
        mInitializer.release();
        mController.release();
        mPlayer = null;
        mVideoFrame.setKeepScreenOn(false);
    }

    // ---------------------------------------------------------------- controls

    private void setupControls() {
        findViewById(R.id.mobile_player_back).setOnClickListener(v -> onBackPressed());
        mPlayButton.setOnClickListener(v -> {
            togglePlay();
            scheduleHide();
        });
        findViewById(R.id.mobile_player_prev).setOnClickListener(v -> {
            mPresenter.onPreviousClicked();
            scheduleHide();
        });
        findViewById(R.id.mobile_player_next).setOnClickListener(v -> {
            mPresenter.onNextClicked();
            scheduleHide();
        });
        findViewById(R.id.mobile_player_quality).setOnClickListener(v -> clickButton(R.id.lb_control_high_quality));
        mFullscreenButton.setOnClickListener(v -> setFullscreen(!isLandscape()));
        mChannel.setOnClickListener(v -> clickButton(R.id.action_channel));

        mSeek.setMax(1000);
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mTime.setText(formatTime(progress * getDurationMs() / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mSeeking = true;
                mHandler.removeCallbacks(mHideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mSeeking = false;
                long duration = getDurationMs();
                if (duration > 0) {
                    seekTo(seekBar.getProgress() * duration / 1000);
                }
                scheduleHide();
            }
        });
    }

    private void setupGestures() {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                showControls(!isControlsShown());
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                boolean forward = e.getX() > mVideoFrame.getWidth() / 2f;
                long target = getPositionMs() + (forward ? JUMP_MS : -JUMP_MS);
                long duration = getDurationMs();
                if (duration > 0) {
                    target = Math.min(target, duration - 1000);
                }
                seekTo(Math.max(0, target));
                showSeekHint(forward ? "» 10 s" : "« 10 s");
                return true;
            }
        });
        View touch = findViewById(R.id.mobile_touch);
        touch.setOnTouchListener((v, event) -> detector.onTouchEvent(event));
        // The controls cover the video: a tap on their empty part behaves like a tap on the video
        mControls.setOnTouchListener((v, event) -> detector.onTouchEvent(event));
    }

    private void showSeekHint(String text) {
        mSeekHint.setText(text);
        mSeekHint.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideSeekHint);
        mHandler.postDelayed(mHideSeekHint, 700);
    }

    private final Runnable mHideSeekHint = () -> mSeekHint.setVisibility(View.GONE);

    private void togglePlay() {
        if (getPlayWhenReady()) {
            setPlayWhenReady(false);
            mPresenter.onPauseClicked();
        } else {
            if (mPlayer != null && mPlayer.getPlaybackState() == Player.STATE_ENDED) {
                setPositionMs(0);
            }
            setPlayWhenReady(true);
            mPresenter.onPlayClicked();
        }
        updatePlayButton();
    }

    private void seekTo(long positionMs) {
        setPositionMs(positionMs);
        mPresenter.onSeekPositionChanged(positionMs);
        updateProgress();
    }

    private void updatePlayButton() {
        boolean playing = getPlayWhenReady();
        mPlayButton.setImageResource(playing ? R.drawable.mobile_ic_pause : R.drawable.mobile_ic_play);
        mPlayButton.setContentDescription(playing ? "Pausa" : "Reproducir");
    }

    private void updateProgress() {
        if (mPlayer == null || mSeeking) {
            return;
        }
        long duration = getDurationMs();
        long position = getPositionMs();
        mDuration.setText(duration > 0 ? formatTime(duration) : "");
        mTime.setText(formatTime(position));
        mSeek.setProgress(duration > 0 ? (int) (position * 1000 / duration) : 0);
        mSeek.setSecondaryProgress(duration > 0 ? (int) (mPlayer.getBufferedPosition() * 1000 / duration) : 0);
    }

    private static String formatTime(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long total = ms / 1000;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        return hours > 0
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private void scheduleHide() {
        mHandler.removeCallbacks(mHideControls);
        if (getPlayWhenReady()) {
            mHandler.postDelayed(mHideControls, HIDE_CONTROLS_MS);
        }
    }

    // ---------------------------------------------------------------- action buttons

    private void buildActions() {
        mActions.removeAllViews();
        mButtonViews.clear();
        boolean isKid = FamilyProfiles.instance(this).getCurrent().isKid;
        if (!isKid) {
            // Likes, subscriptions and saved videos belong to the Google account: grown-ups only
            addAction(R.id.action_thumbs_up, R.drawable.mobile_ic_like, "Me gusta");
            addAction(R.id.action_subscribe, R.drawable.mobile_ic_subs, "Suscribirse");
            addAction(R.id.action_playlist_add, R.drawable.mobile_ic_save, "Guardar");
        }
        addAction(R.id.lb_control_closed_captioning, R.drawable.mobile_ic_cc, "Subtítulos");
        addAction(R.id.action_video_speed, R.drawable.mobile_ic_speed, "Velocidad");
        addAction(R.id.lb_control_high_quality, R.drawable.mobile_ic_settings, "Calidad");
    }

    private void addAction(int buttonId, int icon, String label) {
        View item = LayoutInflater.from(this).inflate(R.layout.mobile_action_button, mActions, false);
        ((ImageView) item.findViewById(R.id.mobile_action_icon)).setImageResource(icon);
        ((TextView) item.findViewById(R.id.mobile_action_label)).setText(label);
        item.setOnClickListener(v -> clickButton(buttonId));
        mButtonViews.put(buttonId, mActions.getChildCount());
        mActions.addView(item);
        paintButton(buttonId);
    }

    private void clickButton(int buttonId) {
        mPresenter.onButtonClicked(buttonId, getButtonState(buttonId));
    }

    private void paintButton(int buttonId) {
        int index = mButtonViews.get(buttonId, -1);
        if (index < 0 || index >= mActions.getChildCount()) {
            return;
        }
        View item = mActions.getChildAt(index);
        int state = getButtonState(buttonId);
        int color = state == BUTTON_ON ? COLOR_ON : COLOR_OFF;
        ((ImageView) item.findViewById(R.id.mobile_action_icon)).setColorFilter(color, PorterDuff.Mode.SRC_IN);
        TextView label = item.findViewById(R.id.mobile_action_label);
        label.setTextColor(color);
        if (buttonId == R.id.action_subscribe) {
            label.setText(state == BUTTON_ON ? "Suscrito" : "Suscribirse");
        } else if (buttonId == R.id.action_playlist_add) {
            label.setText(state == BUTTON_ON ? "Guardado" : "Guardar");
        }
        item.setVisibility(state == BUTTON_DISABLED ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- orientation

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void setFullscreen(boolean fullscreen) {
        mForcedOrientation = fullscreen ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        setRequestedOrientation(mForcedOrientation);
    }

    /** Like YouTube: the button turns the screen, and once the phone is held that way, rotation is automatic again. */
    private void releaseForcedOrientation(int degrees) {
        if (mForcedOrientation == -1 || degrees == OrientationEventListener.ORIENTATION_UNKNOWN) {
            return;
        }
        boolean heldPortrait = degrees < 20 || degrees > 340;
        boolean heldLandscape = (degrees > 70 && degrees < 110) || (degrees > 250 && degrees < 290);
        boolean matches = mForcedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ? heldPortrait : heldLandscape;
        if (matches) {
            mForcedOrientation = -1;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    private void applyOrientation(int orientation) {
        boolean landscape = orientation == Configuration.ORIENTATION_LANDSCAPE;
        ViewGroup.LayoutParams params = mVideoFrame.getLayoutParams();
        if (landscape) {
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            int width = getResources().getDisplayMetrics().widthPixels;
            params.height = width * 9 / 16;
        }
        mVideoFrame.setLayoutParams(params);
        mDetails.setVisibility(landscape ? View.GONE : View.VISIBLE);
        mOverlayTitle.setVisibility(landscape ? View.VISIBLE : View.INVISIBLE);
        mFullscreenButton.setImageResource(landscape ? R.drawable.mobile_ic_fullscreen_exit : R.drawable.mobile_ic_fullscreen);
        setSystemBarsHidden(landscape);
    }

    @SuppressWarnings("deprecation")
    private void setSystemBarsHidden(boolean hidden) {
        View decor = getWindow().getDecorView();
        if (hidden) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | (Build.VERSION.SDK_INT >= 19 ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0));
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    // ---------------------------------------------------------------- video info

    private void showVideoInfo(Video video) {
        if (video == null) {
            return;
        }
        String title = video.getTitleFull() != null ? video.getTitleFull() : video.getTitle();
        mTitle.setText(title);
        mOverlayTitle.setText(title);
        CharSequence meta = video.getSecondTitleFull();
        mMeta.setText(meta != null ? meta : "");
        mMeta.setVisibility(TextUtils.isEmpty(meta) ? View.GONE : View.VISIBLE);
        String author = video.getAuthor();
        mChannel.setText(!TextUtils.isEmpty(author) ? author : "");
        mChannel.setVisibility(TextUtils.isEmpty(author) ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- suggestions list

    @Override
    public void onVideoClicked(Video video) {
        mPresenter.onSuggestionItemClicked(video);
    }

    @Override
    public void onVideoLongClicked(Video video) {
        MobileVideoMenu.show(this, video, () -> mPresenter.onSuggestionItemLongClicked(video));
    }

    @Override
    public void onChannelClicked(FamilyChannelItem channel) {
        // No channel strip in the player
    }

    @Override
    public void onNearEnd() {
        Video last = mFeed.lastVideo();
        if (last != null && last != mLastScrollEnd) {
            mLastScrollEnd = last;
            mPresenter.onScrollEnd(last);
        }
    }

    private int findGroup(VideoGroup group) {
        for (int i = 0; i < mGroups.size(); i++) {
            if (mGroups.get(i).getId() == group.getId()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void updateSuggestions(VideoGroup group) {
        if (group == null) {
            return;
        }
        int index = findGroup(group);
        if (index < 0) {
            mGroups.add(group);
        } else {
            mGroups.set(index, group);
        }
        mFeed.apply(group);
        mAdapter.setItems(mFeed.flatten());
    }

    @Override
    public void removeSuggestions(VideoGroup group) {
        if (group == null) {
            return;
        }
        int index = findGroup(group);
        if (index >= 0) {
            mGroups.remove(index);
        }
        mFeed.removeBlock(group.getId());
        mAdapter.setItems(mFeed.flatten());
    }

    @Override
    public int getSuggestionsIndex(VideoGroup group) {
        return group != null ? findGroup(group) : -1;
    }

    @Override
    public VideoGroup getSuggestionsByIndex(int index) {
        return index >= 0 && index < mGroups.size() ? mGroups.get(index) : null;
    }

    @Override
    public void focusSuggestedItem(int index) {
        // Touch: the user scrolls by themselves
    }

    @Override
    public void focusSuggestedItem(Video video) {
        // Touch: the user scrolls by themselves
    }

    @Override
    public void resetSuggestedPosition() {
        mList.scrollToPosition(0);
    }

    @Override
    public boolean isSuggestionsEmpty() {
        return mGroups.isEmpty();
    }

    @Override
    public void clearSuggestions() {
        mGroups.clear();
        mFeed.clear();
        mLastScrollEnd = null;
        mAdapter.setItems(new ArrayList<>());
    }

    // ---------------------------------------------------------------- PlayerUI

    @Override
    public void showOverlay(boolean show) {
        showControls(show);
    }

    @Override
    public boolean isOverlayShown() {
        return isControlsShown();
    }

    @Override
    public void showSuggestions(boolean show) {
        // Always below the video in portrait
    }

    @Override
    public boolean isSuggestionsShown() {
        return false;
    }

    @Override
    public void showControls(boolean show) {
        boolean wasShown = isControlsShown();
        mControls.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            updateProgress();
            updatePlayButton();
            scheduleHide();
        } else {
            mHandler.removeCallbacks(mHideControls);
            if (isLandscape()) {
                setSystemBarsHidden(true);
            }
        }
        if (wasShown != show) {
            mPresenter.onControlsShown(show);
        }
    }

    @Override
    public boolean isControlsShown() {
        return mControls.getVisibility() == View.VISIBLE;
    }

    @Override
    public int getButtonState(int buttonId) {
        return mButtonStates.get(buttonId, BUTTON_OFF);
    }

    @Override
    public void setButtonState(int buttonId, int buttonState) {
        mButtonStates.put(buttonId, buttonState);
        paintButton(buttonId);
    }

    @Override
    public void setChannelIcon(String iconUrl) {
        // The channel name is enough on the phone
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
        if (mPlayerView.getSubtitleView() != null) {
            mPlayerView.getSubtitleView().setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void loadStoryboard() {
    }

    @Override
    public void setTitle(String title) {
        if (title != null) {
            mTitle.setText(title);
            mOverlayTitle.setText(title);
        }
    }

    @Override
    public void showProgressBar(boolean show) {
        if (mController.isLoading() || mController.isBuffering()) {
            return; // the player shows its own buffering
        }
        mBuffering.setVisibility(show ? View.VISIBLE : View.GONE);
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

    // ---------------------------------------------------------------- PlayerManager

    @Override
    public void setVideo(Video video) {
        mController.setVideo(video);
        showVideoInfo(video);
    }

    @Override
    public Video getVideo() {
        return mController != null ? mController.getVideo() : null;
    }

    @Override
    public void showBackground(String url) {
    }

    @Override
    public void showBackgroundColor(int colorResId) {
    }

    @Override
    public void resetPlayerState() {
        mController.resetPlayerState();
    }

    @Override
    public boolean isEmbed() {
        return false;
    }

    // ---------------------------------------------------------------- PlayerEngine

    @Override
    public void openSabr(MediaItemFormatInfo formatInfo) {
        mController.openSabr(formatInfo);
    }

    @Override
    public void openDash(MediaItemFormatInfo formatInfo) {
        mController.openDash(formatInfo);
    }

    @Override
    public void openDash(InputStream dashManifest) {
        mController.openDash(dashManifest);
    }

    @Override
    public void openDashUrl(String dashManifestUrl) {
        mController.openDashUrl(dashManifestUrl);
    }

    @Override
    public void openHlsUrl(String hlsPlaylistUrl) {
        mController.openHlsUrl(hlsPlaylistUrl);
    }

    @Override
    public void openUrlList(List<String> urlList) {
        mController.openUrlList(urlList);
    }

    @Override
    public void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
        mController.openMerged(formatInfo, hlsPlaylistUrl);
    }

    @Override
    public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        mController.openMerged(dashManifest, hlsPlaylistUrl);
    }

    @Override
    public long getPositionMs() {
        return mController.getPositionMs();
    }

    @Override
    public void setPositionMs(long positionMs) {
        mController.setPositionMs(positionMs);
    }

    @Override
    public long getDurationMs() {
        return mController.getDurationMs();
    }

    @Override
    public void setPlayWhenReady(boolean play) {
        mController.setPlayWhenReady(play);
        updatePlayButton();
    }

    @Override
    public boolean getPlayWhenReady() {
        return mController.getPlayWhenReady();
    }

    @Override
    public boolean isPlaying() {
        return mController.isPlaying();
    }

    @Override
    public boolean isLoading() {
        return mController.isLoading();
    }

    @Override
    public List<FormatItem> getVideoFormats() {
        return mController.getVideoFormats();
    }

    @Override
    public List<FormatItem> getAudioFormats() {
        return mController.getAudioFormats();
    }

    @Override
    public List<FormatItem> getSubtitleFormats() {
        return mController.getSubtitleFormats();
    }

    @Override
    public void setFormat(FormatItem option) {
        mController.selectFormat(option);
    }

    @Override
    public FormatItem getVideoFormat() {
        return mController.getVideoFormat();
    }

    @Override
    public FormatItem getAudioFormat() {
        return mController.getAudioFormat();
    }

    @Override
    public FormatItem getSubtitleFormat() {
        return mController.getSubtitleFormat();
    }

    @Override
    public boolean isEngineInitialized() {
        return mPlayer != null;
    }

    @Override
    public void restartEngine() {
        if (isFinishing()) {
            return;
        }
        releasePlayer();
        initializePlayer();
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
        return mController.containsMedia();
    }

    @Override
    public void setSpeed(float speed) {
        mController.setSpeed(speed);
        setButtonState(R.id.action_video_speed, speed != 1.0f ? PlayerUI.BUTTON_ON : PlayerUI.BUTTON_OFF);
    }

    @Override
    public float getSpeed() {
        return mController.getSpeed();
    }

    @Override
    public void setPitch(float pitch) {
        mController.setPitch(pitch);
    }

    @Override
    public float getPitch() {
        return mController.getPitch();
    }

    @Override
    public void setVolume(float volume) {
        mController.setVolume(volume);
    }

    @Override
    public float getVolume() {
        return mController.getVolume();
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
}
