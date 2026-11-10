package es.myfamilytube.ui;

import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import es.myfamilytube.FamilyDiagnostics;
import es.myfamilytube.profiles.FamilyAccounts;
import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;

/**
 * MyFamilyTube home on the phone: the same engine, profiles and parental control as the TV,
 * shown as a list you scroll with the finger and a bar of tabs at the bottom.
 */
public class MobileHomeActivity extends MotherActivity implements BrowseView, MobileFeedAdapter.Listener {
    private static final int[] HOME_CANDIDATES = {MediaGroup.TYPE_HOME, MediaGroup.TYPE_TRENDING, MediaGroup.TYPE_MUSIC};
    /** A section seen less than this long ago comes back instantly from memory. */
    private static final long CACHE_MAX_AGE_MS = 15 * 60 * 1000;
    private static final long WELCOME_MIN_MS = 1800;
    private static final long WELCOME_MAX_MS = 6000;
    private static final int COLOR_ON = 0xFFFF3D33;
    private static final int COLOR_OFF = 0xFFA7ABB3;

    /** Set whenever the app comes to the front; the next resume shows the welcome screen. */
    private static boolean sWelcomePending = true;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private BrowsePresenter mPresenter;

    private final List<BrowseSection> mSections = new ArrayList<>();
    private final List<int[]> mTabs = new ArrayList<>(); // {sectionId, iconRes}
    private final List<String> mTabLabels = new ArrayList<>();
    private BrowseSection mCurrentSection;
    private BrowseSection mSettingsSection;

    /** Each section keeps its own list, so going back to a tab is instant. */
    private final Map<Integer, MobileFeed> mFeeds = new HashMap<>();
    private final Map<Integer, Long> mFeedTimes = new HashMap<>();
    private final List<Object> mSettingsItems = new ArrayList<>();

    private MobileFeedAdapter mAdapter;
    private RecyclerView mList;
    private LinearLayout mNav;
    private TextView mLogo;
    private TextView mProfileButton;
    private View mSettingsButton;
    private View mMessage;
    private TextView mMessageText;
    private TextView mMessageAction;
    private ProgressBar mProgress;
    private View mWelcome;
    private FamilyProfilesOverlay mProfilesOverlay;
    private FamilySettingsOverlay mSettingsOverlay;
    private MobilePreview mPreview;
    private final MobileHaptics mHaptics = new MobileHaptics();

    private long mLoadStartMs;
    private int mPendingUpdates;
    private Video mLastScrollEnd;
    private Runnable mWelcomeMin;
    private Runnable mWelcomeMax;
    private Runnable mOnPrefetchDone;
    private int mPrefetchRunning;
    private boolean mPrefetchPending = true;
    private final List<io.reactivex.disposables.Disposable> mPrefetch = new ArrayList<>();
    private final Set<String> mKidsSectionTried = new HashSet<>();

    /** A YouTube link opened from another app, waiting for the profile to be chosen. */
    private static android.content.Intent sPendingLink;
    private static boolean sAlive;

    public static void onAppForeground() {
        sWelcomePending = true;
    }

    static void setPendingLink(android.content.Intent link) {
        sPendingLink = link;
    }

    static boolean isAlive() {
        return sAlive;
    }

    @Override
    protected void initTheme() {
        setTheme(R.style.MobileTheme);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sAlive = true;
        setContentView(R.layout.mobile_home);

        mList = findViewById(R.id.mobile_list);
        mNav = findViewById(R.id.mobile_nav);
        mLogo = findViewById(R.id.family_logo);
        mProfileButton = findViewById(R.id.family_profile_button);
        mSettingsButton = findViewById(R.id.family_settings);
        mMessage = findViewById(R.id.family_message);
        mMessageText = findViewById(R.id.family_message_text);
        mMessageAction = findViewById(R.id.family_message_action);
        mProgress = findViewById(R.id.family_progress);
        mWelcome = findViewById(R.id.family_welcome);

        mList.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new MobileFeedAdapter(this, false);
        mPreview = new MobilePreview(this);
        mAdapter.setPreview(mPreview);
        mList.setAdapter(mAdapter);
        mList.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View view) {
            }

            @Override
            public void onChildViewDetachedFromWindow(View view) {
                mPreview.onDetached(view);
            }
        });

        ViewGroup root = findViewById(R.id.mobile_root);
        mProfilesOverlay = new FamilyProfilesOverlay(this, root, profile -> {
            // A different profile reloads through the FamilyProfiles listener
            updateProfileButton();
            updateGreeting();
            mHandler.post(this::runPendingLink);
        });
        mSettingsOverlay = new FamilySettingsOverlay(this, root, new FamilySettingsOverlay.Host() {
            @Override
            public void openProfiles() {
                mProfilesOverlay.showManage();
            }

            @Override
            public void openDiagnostics() {
                FamilyDiagnosticsDialog.show(MobileHomeActivity.this);
            }

            @Override
            public void openAdvanced() {
                if (mSettingsSection != null) {
                    openSection(mSettingsSection);
                }
            }

            @Override
            public void onCardSizeChanged() {
                // TV only
            }
        });

        findViewById(R.id.mobile_search).setOnClickListener(v -> SearchPresenter.instance(this).startSearch(null));
        mProfileButton.setOnClickListener(v -> mProfilesOverlay.show());
        mSettingsButton.setOnClickListener(v -> {
            if (!currentProfile().isKid) {
                mSettingsOverlay.show();
            }
        });
        updateProfileButton();
        FamilyProfiles.instance(this).addListener(() -> mHandler.post(this::onProfileChanged));

        mPresenter = BrowsePresenter.instance(this);
        mPresenter.setView(this);
        enableKidsSectionOnce();
        mPresenter.onViewInitialized();

        String problem = FamilyDiagnostics.instance(this).takePendingProblem();
        if (problem != null && !currentProfile().isKid) {
            FamilyDiagnosticsDialog.showProblem(this, problem);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPresenter.setView(this);
        mPresenter.onViewResumed();
        mProfilesOverlay.onResumed(); // back from adding a Google account?
        updateGreeting();
        // Something may have been watched meanwhile: history must be fresh
        mFeeds.remove(MediaGroup.TYPE_HISTORY);
        if (mCurrentSection != null && mCurrentSection.getId() == MediaGroup.TYPE_HISTORY) {
            reloadCurrent();
        }

        if (sWelcomePending) {
            sWelcomePending = false;
            mSettingsOverlay.hide();
            mProfilesOverlay.hide();
            showWelcome();
        } else if (mWelcome.getVisibility() != View.VISIBLE
                && FamilyProfiles.instance(this).shouldAskWhoIsWatching() && !mProfilesOverlay.isShowing()) {
            mProfilesOverlay.showMandatory();
        } else if (FamilyProfiles.instance(this).isTimeUp(currentProfile())) {
            showTimeUp();
        } else if (mWelcome.getVisibility() != View.VISIBLE && !mProfilesOverlay.isShowing()) {
            runPendingLink();
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    /** Opens the waiting YouTube link (video, list, channel or search) for the profile now watching. */
    private void runPendingLink() {
        android.content.Intent link = sPendingLink;
        if (link == null || isFinishing() || FamilyProfiles.instance(this).shouldAskWhoIsWatching()) {
            return;
        }
        sPendingLink = null;
        if (FamilyProfiles.instance(this).isTimeUp(currentProfile())) {
            showTimeUp();
            return;
        }
        String videoId = com.liskovsoft.smartyoutubetv2.common.utils.IntentExtractor.extractVideoId(link);
        if (videoId != null) {
            long timeMs = com.liskovsoft.smartyoutubetv2.common.utils.IntentExtractor.extractVideoTimeMs(link);
            com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(this)
                    .openVideo(videoId, false, timeMs, false);
            return;
        }
        String playlistId = com.liskovsoft.smartyoutubetv2.common.utils.IntentExtractor.extractPlaylistId(link);
        if (playlistId != null) {
            Video video = new Video();
            video.playlistId = playlistId;
            com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter.instance(this).openChannel(video);
            return;
        }
        String channelId = com.liskovsoft.smartyoutubetv2.common.utils.IntentExtractor.extractChannelId(link);
        if (channelId != null) {
            ChannelPresenter.instance(this).openChannel(channelId);
            return;
        }
        String searchText = com.liskovsoft.smartyoutubetv2.common.utils.IntentExtractor.extractSearchText(link);
        if (searchText != null) {
            SearchPresenter.instance(this).startSearch(searchText);
            return;
        }
        android.widget.Toast.makeText(this, "No se ha podido abrir ese enlace", android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPreview.release();
        mPresenter.onViewPaused();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        sAlive = false;
        disposePrefetch();
        if (mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mWelcome.getVisibility() == View.VISIBLE) {
            finish();
            return;
        }
        if (mSettingsOverlay.onBack() || mProfilesOverlay.onBack()) {
            return;
        }
        if (mCurrentSection != null && mCurrentSection == mSettingsSection) {
            openSection(firstTabSection());
            mSettingsOverlay.show();
            return;
        }
        BrowseSection first = firstTabSection();
        if (first != null && mCurrentSection != null && mCurrentSection.getId() != first.getId()) {
            openSection(first);
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- profile

    private FamilyProfile currentProfile() {
        return FamilyProfiles.instance(this).getCurrent();
    }

    private void updateProfileButton() {
        FamilyAvatars.apply(mProfileButton, currentProfile(), false, 0);
    }

    /** Kids see "Hola, Leo · quedan 35 min" instead of the app name. */
    private void updateGreeting() {
        FamilyProfile profile = currentProfile();
        if (!profile.isKid) {
            mLogo.setText(R.string.app_name);
            return;
        }
        int left = FamilyProfiles.instance(this).getMinutesLeft(profile);
        mLogo.setText("Hola, " + profile.name + (left >= 0 ? " · quedan " + left + " min" : ""));
    }

    /** Profile switched or its settings changed: same account, rules and tabs as on the TV. */
    private void onProfileChanged() {
        if (isFinishing()) {
            return;
        }
        updateProfileButton();
        updateGreeting();
        boolean accountChanged = FamilyAccounts.applyFor(this, currentProfile());
        disposePrefetch();
        // Every list was filtered for the previous profile: start again
        mFeeds.clear();
        mFeedTimes.clear();
        rebuildTabs();
        mCurrentSection = null;
        mPrefetchPending = !currentProfile().isKid;
        BrowseSection first = firstTabSection();
        if (first != null) {
            openSection(first);
        }
        if (accountChanged) {
            FamilyDiagnostics.instance(this).log("Cuenta de Google cambiada para «" + currentProfile().name + "»");
        }
    }

    private void enableKidsSectionOnce() {
        SharedPreferences prefs = getSharedPreferences("family_ui", MODE_PRIVATE);
        if (!prefs.getBoolean("kids_section_enabled", false)) {
            mPresenter.enableSection(MediaGroup.TYPE_KIDS_HOME, true);
            prefs.edit().putBoolean("kids_section_enabled", true).apply();
        }
    }

    /** The engine keeps the sections per Google account: turn YouTube Kids on for each one once. */
    private final Runnable mKidsSectionCheck = () -> {
        if (isFinishing() || mSections.isEmpty() || !currentProfile().isKid
                || findSection(MediaGroup.TYPE_KIDS_HOME) != null) {
            return;
        }
        if (mKidsSectionTried.add(FamilyAccounts.currentKey())) {
            mPresenter.enableSection(MediaGroup.TYPE_KIDS_HOME, true);
        }
    };

    // ---------------------------------------------------------------- tabs

    private BrowseSection findSection(int sectionId) {
        for (BrowseSection section : mSections) {
            if (section.getId() == sectionId) {
                return section;
            }
        }
        return null;
    }

    private BrowseSection firstTabSection() {
        return mTabs.isEmpty() ? null : findSection(mTabs.get(0)[0]);
    }

    private boolean isTab(BrowseSection section) {
        for (int[] tab : mTabs) {
            if (tab[0] == section.getId()) {
                return true;
            }
        }
        return false;
    }

    private void rebuildTabs() {
        mTabs.clear();
        mTabLabels.clear();
        mSettingsSection = findSection(MediaGroup.TYPE_SETTINGS);
        boolean isKid = currentProfile().isKid;

        BrowseSection home = isKid ? findSection(MediaGroup.TYPE_KIDS_HOME) : null;
        for (int i = 0; home == null && i < HOME_CANDIDATES.length; i++) {
            home = findSection(HOME_CANDIDATES[i]);
        }
        if (home != null) {
            addTab(home.getId(), "Inicio", R.drawable.mobile_ic_home);
        }
        // Subscriptions and history belong to the account: not for kids
        if (!isKid && findSection(MediaGroup.TYPE_SUBSCRIPTIONS) != null) {
            addTab(MediaGroup.TYPE_SUBSCRIPTIONS, "Suscripciones", R.drawable.mobile_ic_subs);
        }
        if (!isKid && findSection(MediaGroup.TYPE_HISTORY) != null) {
            addTab(MediaGroup.TYPE_HISTORY, "Biblioteca", R.drawable.mobile_ic_library);
        }
        mSettingsButton.setVisibility(mSettingsSection != null && !isKid ? View.VISIBLE : View.GONE);
        drawNav();

        mHandler.removeCallbacks(mKidsSectionCheck);
        mHandler.post(mKidsSectionCheck);
    }

    private void addTab(int sectionId, String label, int icon) {
        mTabs.add(new int[]{sectionId, icon});
        mTabLabels.add(label);
    }

    private void drawNav() {
        mNav.removeAllViews();
        // A single tab (kids) needs no bar
        mNav.setVisibility(mTabs.size() > 1 ? View.VISIBLE : View.GONE);
        int current = mCurrentSection != null ? mCurrentSection.getId() : Integer.MIN_VALUE;
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < mTabs.size(); i++) {
            int[] tab = mTabs.get(i);
            View item = inflater.inflate(R.layout.mobile_nav_item, mNav, false);
            ImageView icon = item.findViewById(R.id.mobile_nav_icon);
            TextView label = item.findViewById(R.id.mobile_nav_label);
            icon.setImageResource(tab[1]);
            label.setText(mTabLabels.get(i));
            int color = tab[0] == current ? COLOR_ON : COLOR_OFF;
            icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            label.setTextColor(color);
            int sectionId = tab[0];
            item.setOnClickListener(v -> {
                BrowseSection section = findSection(sectionId);
                if (section == null) {
                    return;
                }
                if (mCurrentSection != null && mCurrentSection.getId() == sectionId) {
                    onSameTabTapped();
                } else {
                    openSection(section);
                }
            });
            mNav.addView(item);
        }
    }

    /** Tapping the open tab again: up to the top, and if already there, reload it. */
    private void onSameTabTapped() {
        LinearLayoutManager layout = (LinearLayoutManager) mList.getLayoutManager();
        if (layout != null && layout.findFirstCompletelyVisibleItemPosition() > 0) {
            mList.smoothScrollToPosition(0);
        } else {
            reloadCurrent();
        }
    }

    private void reloadCurrent() {
        if (mCurrentSection == null || mCurrentSection == mSettingsSection) {
            return;
        }
        int id = mCurrentSection.getId();
        mFeeds.remove(id);
        mFeedTimes.remove(id);
        BrowseSection section = mCurrentSection;
        mCurrentSection = null;
        openSection(section);
    }

    private void openSection(BrowseSection section) {
        if (section == null) {
            return;
        }
        if (section == mSettingsSection && currentProfile().isKid) {
            return;
        }
        boolean changed = mCurrentSection == null || mCurrentSection.getId() != section.getId();
        mCurrentSection = section;
        drawNav();
        if (FamilyProfiles.instance(this).isTimeUp(currentProfile())) {
            showTimeUp();
            return;
        }
        if (!changed) {
            return;
        }
        hideMessage();
        mPreview.stop();
        mLastScrollEnd = null;
        mList.scrollToPosition(0);
        if (section == mSettingsSection) {
            mSettingsItems.clear();
            render();
            mPresenter.onSectionFocused(section.getId());
            return;
        }
        int id = section.getId();
        MobileFeed feed = mFeeds.get(id);
        Long time = mFeedTimes.get(id);
        if (feed != null && !feed.isEmpty() && time != null && System.currentTimeMillis() - time < CACHE_MAX_AGE_MS) {
            render();
            // Instant: the engine just switches section without downloading it again
            mPresenter.onSectionFocusedNoReload(id);
            return;
        }
        feed = new MobileFeed();
        feed.setTop(kidChannelsFor(section));
        mFeeds.put(id, feed);
        mFeedTimes.put(id, System.currentTimeMillis());
        render();
        mLoadStartMs = System.currentTimeMillis();
        mPresenter.onSectionFocused(id);
    }

    /** On a kid's Inicio, their approved channels come first. */
    private List<Object> kidChannelsFor(BrowseSection section) {
        FamilyProfile profile = currentProfile();
        List<Object> top = new ArrayList<>();
        BrowseSection first = firstTabSection();
        if (!profile.isKid || profile.approvedChannels.isEmpty() || first == null || first.getId() != section.getId()) {
            return top;
        }
        List<FamilyChannelItem> channels = new ArrayList<>();
        for (Map.Entry<String, String> entry : profile.approvedChannels.entrySet()) {
            channels.add(new FamilyChannelItem(entry.getKey(), entry.getValue()));
        }
        top.add(new MobileFeed.Header("Tus canales"));
        top.add(new MobileFeedAdapter.ChannelStrip(channels));
        return top;
    }

    private MobileFeed currentFeed() {
        return mCurrentSection != null ? mFeeds.get(mCurrentSection.getId()) : null;
    }

    private void render() {
        if (mCurrentSection != null && mCurrentSection == mSettingsSection) {
            List<Object> items = new ArrayList<>();
            items.add(new MobileFeed.Header("Ajustes avanzados"));
            items.addAll(mSettingsItems);
            mAdapter.setItems(items);
            return;
        }
        MobileFeed feed = currentFeed();
        mAdapter.setItems(feed != null ? feed.flatten() : new ArrayList<>());
    }

    // ---------------------------------------------------------------- list events

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        mHaptics.onTouch(this, event);
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onVideoClicked(Video video) {
        mPreview.stop();
        mPresenter.onVideoItemClicked(video);
    }

    @Override
    public void onThumbnailClicked(View card, Video video) {
        if (!mPreview.onThumbnailTapped(card, video)) {
            onVideoClicked(video);
        }
    }

    @Override
    public void onVideoLongClicked(Video video) {
        MobileVideoMenu.show(this, video, () -> mPresenter.onVideoItemLongClicked(video));
    }

    @Override
    public void onChannelClicked(FamilyChannelItem channel) {
        ChannelPresenter.instance(this).openChannel(channel.channelId);
    }

    @Override
    public void onNearEnd() {
        MobileFeed feed = currentFeed();
        Video last = feed != null ? feed.lastVideo() : null;
        if (last != null && last != mLastScrollEnd) {
            mLastScrollEnd = last;
            mPresenter.onScrollEnd(last);
        }
    }

    // ---------------------------------------------------------------- messages, time up

    private void showMessage(String text, String action, Runnable onAction) {
        mMessage.setVisibility(View.VISIBLE);
        mMessageText.setText(text);
        if (onAction != null) {
            mMessageAction.setVisibility(View.VISIBLE);
            mMessageAction.setText(!TextUtils.isEmpty(action) ? action : "Reintentar");
            mMessageAction.setOnClickListener(v -> onAction.run());
        } else {
            mMessageAction.setVisibility(View.GONE);
        }
    }

    private void hideMessage() {
        mMessage.setVisibility(View.GONE);
    }

    /** A kid used all of today's time: nothing more until a grown-up adds time with the PIN. */
    private void showTimeUp() {
        mAdapter.setItems(new ArrayList<>());
        showMessage("Se ha acabado el tiempo de hoy.\nMañana podrás ver más vídeos.", "Más tiempo (PIN)",
                () -> FamilyPinPad.verify(this, () -> {
                    FamilyProfiles.instance(this).grantExtraMinutes(currentProfile(), 30);
                    hideMessage();
                    onProfileChanged();
                }));
    }

    // ---------------------------------------------------------------- welcome screen

    private void showWelcome() {
        if (mWelcomeMin != null) {
            mHandler.removeCallbacks(mWelcomeMin);
        }
        if (mWelcomeMax != null) {
            mHandler.removeCallbacks(mWelcomeMax);
        }
        mWelcome.animate().cancel();
        mWelcome.setAlpha(1f);
        mWelcome.setVisibility(View.VISIBLE);

        final boolean[] minTimePassed = {false};
        final boolean[] dataLoaded = {false};
        Runnable maybeHide = () -> {
            if (minTimePassed[0] && dataLoaded[0]) {
                hideWelcome();
            }
        };
        mOnPrefetchDone = () -> {
            dataLoaded[0] = true;
            maybeHide.run();
        };
        mWelcomeMin = () -> {
            minTimePassed[0] = true;
            maybeHide.run();
        };
        mWelcomeMax = this::hideWelcome;
        mHandler.postDelayed(mWelcomeMin, WELCOME_MIN_MS);
        mHandler.postDelayed(mWelcomeMax, WELCOME_MAX_MS);
        startPrefetch();
    }

    private void hideWelcome() {
        if (mWelcome.getVisibility() != View.VISIBLE || isFinishing()) {
            return;
        }
        mOnPrefetchDone = null;
        mHandler.removeCallbacks(mWelcomeMin);
        mHandler.removeCallbacks(mWelcomeMax);
        mWelcome.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            mWelcome.setVisibility(View.GONE);
            mWelcome.setAlpha(1f);
        }).start();
        if (FamilyProfiles.instance(this).shouldAskWhoIsWatching()) {
            mProfilesOverlay.showMandatory();
        } else if (FamilyProfiles.instance(this).isTimeUp(currentProfile())) {
            showTimeUp();
        } else {
            runPendingLink();
        }
    }

    // ---------------------------------------------------------------- preloading

    private void disposePrefetch() {
        for (io.reactivex.disposables.Disposable action : mPrefetch) {
            action.dispose();
        }
        mPrefetch.clear();
        mPrefetchRunning = 0;
    }

    /** Loads Suscripciones and Biblioteca in the background so they open instantly (grown-ups only). */
    private void startPrefetch() {
        mPrefetchPending = false;
        if (mPrefetchRunning == 0 && !currentProfile().isKid) {
            com.liskovsoft.mediaserviceinterfaces.ContentService content =
                    com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getContentService();
            prefetch(MediaGroup.TYPE_SUBSCRIPTIONS, content.getSubscriptionsObserve());
            prefetch(MediaGroup.TYPE_HISTORY, content.getHistoryObserve());
        }
        checkPrefetchDone();
    }

    private void prefetch(int sectionId, io.reactivex.Observable<MediaGroup> source) {
        BrowseSection section = findSection(sectionId);
        Long time = mFeedTimes.get(sectionId);
        boolean fresh = mFeeds.containsKey(sectionId) && time != null && System.currentTimeMillis() - time < CACHE_MAX_AGE_MS;
        if (section == null || source == null || fresh || (mCurrentSection != null && mCurrentSection.getId() == sectionId)) {
            return;
        }
        long start = System.currentTimeMillis();
        mPrefetchRunning++;
        mPrefetch.add(source.subscribe(
                mediaGroup -> runOnUiThread(() -> {
                    mPrefetchRunning = Math.max(0, mPrefetchRunning - 1);
                    if (!isFinishing() && mediaGroup != null
                            && (mCurrentSection == null || mCurrentSection.getId() != sectionId)) {
                        MobileFeed feed = new MobileFeed();
                        feed.apply(VideoGroup.from(mediaGroup, section));
                        if (!feed.isEmpty()) {
                            mFeeds.put(sectionId, feed);
                            mFeedTimes.put(sectionId, System.currentTimeMillis());
                            FamilyDiagnostics.instance(this).log("«" + section.getTitle() + "» precargada en "
                                    + (System.currentTimeMillis() - start) + " ms");
                        }
                    }
                    checkPrefetchDone();
                }),
                error -> runOnUiThread(() -> {
                    mPrefetchRunning = Math.max(0, mPrefetchRunning - 1);
                    FamilyDiagnostics.instance(this).log("No se pudo precargar «" + section.getTitle() + "»: " + error.getMessage());
                    checkPrefetchDone();
                })));
    }

    private void checkPrefetchDone() {
        if (mPrefetchRunning == 0 && mOnPrefetchDone != null) {
            Runnable done = mOnPrefetchDone;
            mOnPrefetchDone = null;
            done.run();
        }
    }

    // ---------------------------------------------------------------- BrowseView

    @Override
    public void addSection(int index, BrowseSection section) {
        if (section == null) {
            return;
        }
        BrowseSection existing = findSection(section.getId());
        if (existing != null) {
            mSections.remove(existing);
        }
        int position = index < 0 || index > mSections.size() ? mSections.size() : index;
        mSections.add(position, section);
        rebuildTabs();
    }

    @Override
    public void removeSection(BrowseSection section) {
        if (section == null) {
            return;
        }
        BrowseSection existing = findSection(section.getId());
        if (existing != null) {
            mSections.remove(existing);
            rebuildTabs();
        }
    }

    @Override
    public void removeAllSections() {
        mSections.clear();
        rebuildTabs();
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        BrowseSection target = index >= 0 && index < mSections.size() ? mSections.get(index) : null;
        if (target == null || !(isTab(target) || target == mSettingsSection)) {
            // The engine may start on a section we don't show: open our first tab
            target = firstTabSection();
        }
        openSection(target);
    }

    @Override
    public void updateSection(VideoGroup group) {
        // Next frame: changing a list while it is being laid out crashes RecyclerView
        mPendingUpdates++;
        mHandler.post(() -> {
            mPendingUpdates--;
            if (isFinishing() || group == null || group.isShorts() || mCurrentSection == null) {
                return;
            }
            BrowseSection section = group.getSection();
            if (section != null && section.getId() != mCurrentSection.getId()) {
                return;
            }
            MobileFeed feed = currentFeed();
            if (feed == null) {
                return;
            }
            boolean fullReload = group.getAction() == VideoGroup.ACTION_REPLACE && group.getPosition() == -1;
            feed.apply(group);
            if (fullReload) {
                feed.setTop(kidChannelsFor(mCurrentSection));
            }
            mFeedTimes.put(mCurrentSection.getId(), System.currentTimeMillis());
            render();
            if (!feed.isEmpty()) {
                hideMessage();
                onContentArrived();
            }
        });
    }

    private void onContentArrived() {
        if (mLoadStartMs > 0 && mCurrentSection != null) {
            FamilyDiagnostics.instance(this).log("«" + mCurrentSection.getTitle() + "» cargada en "
                    + (System.currentTimeMillis() - mLoadStartMs) + " ms");
            mLoadStartMs = 0;
            if (mPrefetchPending) {
                // Inicio is on screen: now fetch the other tabs, a moment later so it isn't slowed down
                mPrefetchPending = false;
                mHandler.postDelayed(this::startPrefetch, 1500);
            }
        }
    }

    @Override
    public void updateSection(SettingsGroup group) {
        mHandler.post(() -> {
            if (isFinishing() || group == null || mCurrentSection != mSettingsSection) {
                return;
            }
            mSettingsItems.clear();
            mSettingsItems.addAll(group.getItems());
            render();
        });
    }

    @Override
    public void clearSection(BrowseSection section) {
        mHandler.post(() -> {
            if (isFinishing() || section == null || mCurrentSection == null || section.getId() != mCurrentSection.getId()) {
                return;
            }
            MobileFeed feed = currentFeed();
            if (feed != null) {
                feed.clear();
            }
            render();
        });
    }

    @Override
    public void selectSectionItem(int index) {
        // Touch lists keep the user's own scroll position
    }

    @Override
    public void selectSectionItem(Video item) {
        // Touch lists keep the user's own scroll position
    }

    @Override
    public void showError(ErrorFragmentData data) {
        if (data == null) {
            hideMessage();
            return;
        }
        mLoadStartMs = 0;
        FamilyDiagnostics.instance(this).log("Error al cargar «" + (mCurrentSection != null ? mCurrentSection.getTitle() : "?")
                + "»: " + data.getMessage());
        showMessage(data.getMessage(), data.getActionText(), data::onAction);
    }

    @Override
    public void showProgressBar(boolean show) {
        mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean isProgressBarShowing() {
        return mProgress.getVisibility() == View.VISIBLE;
    }

    @Override
    public void focusOnContent() {
        // Nothing to focus on a touch screen
    }

    @Override
    public boolean isEmpty() {
        MobileFeed feed = currentFeed();
        return (feed == null || feed.isEmpty()) && mPendingUpdates == 0;
    }

    @Override
    public void updateBadge() {
        // No badge in this design
    }
}
