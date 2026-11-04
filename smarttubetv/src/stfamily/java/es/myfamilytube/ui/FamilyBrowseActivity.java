package es.myfamilytube.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.leanback.widget.VerticalGridView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.ContentGate;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;

import java.util.ArrayList;
import java.util.List;

import es.myfamilytube.FamilyDiagnostics;
import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;

/**
 * MyFamilyTube home. Replaces SmartTube's BrowseActivity but talks to the same BrowsePresenter,
 * so loading, paging, menus and playback all come from the upstream engine.
 */
public class FamilyBrowseActivity extends LeanbackActivity implements BrowseView {
    /** Tabs shown on top, in this order. The first id the engine offers wins for "Inicio". */
    private static final int[] HOME_CANDIDATES = {MediaGroup.TYPE_HOME, MediaGroup.TYPE_TRENDING, MediaGroup.TYPE_MUSIC};
    private static final int TAB_SEARCH = -1001;
    private static final int GRID_COLUMNS = 5;
    private static final int GRID_ROW_ID_BASE = -2000;
    private static final int CHANNELS_ROW_ID = -3000;
    private static final int SCROLL_CONTINUE_NUM = 6;
    /** Waiting a bit on a tab before loading avoids starting a load for every tab you pass over. */
    private static final long TAB_SWITCH_DELAY_MS = 450;
    /** A section seen less than this long ago comes back instantly from memory. */
    private static final long CACHE_MAX_AGE_MS = 15 * 60 * 1000;

    private BrowsePresenter mPresenter;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // All sections the engine added, in its order (indexes match the presenter's)
    private final List<BrowseSection> mSections = new ArrayList<>();
    private final List<TabInfo> mTabs = new ArrayList<>();
    private BrowseSection mCurrentSection;
    private BrowseSection mSettingsSection;
    private BrowseSection mUpdateSection;

    private final List<FamilyRow> mRows = new ArrayList<>();
    private FamilyRowsAdapter mRowsAdapter;
    // Grid sections (subscriptions, history…) are shown as rows of GRID_COLUMNS cards
    private final List<Video> mGridVideos = new ArrayList<>();

    private LinearLayout mTabsView;
    private VerticalGridView mContent;
    private View mHero;
    private TextView mHeroTitle;
    private TextView mHeroMeta;
    private ImageView mHeroImage;
    private View mMessage;
    private TextView mMessageText;
    private TextView mMessageAction;
    private ProgressBar mProgress;
    private TextView mUpdateButton;
    private View mSettingsButton;
    private Runnable mPendingTabSwitch;
    private String mHeroImageUrl;
    private boolean mFocusContentWhenLoaded;
    private int mPendingUpdates;
    /** When the current section started downloading (0 = not loading), to log how long it takes. */
    private long mLoadStartMs;
    /** Last time a direction key was pressed: tabs only switch section when reached with the remote. */
    private long mLastNavKeyMs;
    private View mWelcome;
    private Runnable mWelcomeMin;
    private Runnable mWelcomeMax;
    /** Set whenever the app comes to the front; the next resume shows the welcome screen. */
    private static boolean sWelcomePending = true;

    public static void onAppForeground() {
        sWelcomePending = true;
    }
    private Runnable mPendingHero;
    private TextView mProfileButton;
    private FamilyProfilesOverlay mProfilesOverlay;
    private FamilySettingsOverlay mSettingsOverlay;

    private static final class TabInfo {
        final int sectionId;
        final TextView view;

        TabInfo(int sectionId, TextView view) {
            this.sectionId = sectionId;
            this.view = view;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.family_browse);

        mTabsView = findViewById(R.id.family_tabs);
        mContent = findViewById(R.id.family_content);
        mHero = findViewById(R.id.family_hero);
        mHeroTitle = findViewById(R.id.family_hero_title);
        mHeroMeta = findViewById(R.id.family_hero_meta);
        mHeroImage = findViewById(R.id.family_hero_image);
        mMessage = findViewById(R.id.family_message);
        mMessageText = findViewById(R.id.family_message_text);
        mMessageAction = findViewById(R.id.family_message_action);
        mProgress = findViewById(R.id.family_progress);
        mUpdateButton = findViewById(R.id.family_update);
        mSettingsButton = findViewById(R.id.family_settings);
        mWelcome = findViewById(R.id.family_welcome);

        mRowsAdapter = new FamilyRowsAdapter(mRows);
        mContent.setAdapter(mRowsAdapter);
        mContent.setItemSpacing(getResources().getDimensionPixelSize(R.dimen.family_card_gap));
        mContent.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_LOW_EDGE);
        mContent.setWindowAlignmentOffsetPercent(VerticalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);
        mContent.setItemAlignmentOffsetPercent(VerticalGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED);
        // Up from the first row reaching the tabs: lb:focusOutFront="true" in family_browse.xml

        mSettingsOverlay = new FamilySettingsOverlay(this, findViewById(R.id.family_root), new FamilySettingsOverlay.Host() {
            @Override
            public void openProfiles() {
                mProfilesOverlay.showManage();
            }

            @Override
            public void openDiagnostics() {
                FamilyDiagnosticsDialog.show(FamilyBrowseActivity.this);
            }

            @Override
            public void openAdvanced() {
                if (mSettingsSection != null) {
                    mSettingsButton.requestFocus(); // keep focus off the tabs
                    openSection(mSettingsSection, true);
                }
            }
        });
        mSettingsButton.setOnClickListener(v -> {
            if (!currentProfile().isKid) {
                mSettingsOverlay.show();
            }
        });
        mUpdateButton.setOnClickListener(v -> {
            if (mUpdateSection != null && mUpdateSection.getData() instanceof ErrorFragmentData) {
                ((ErrorFragmentData) mUpdateSection.getData()).onAction();
            }
        });

        mProfileButton = findViewById(R.id.family_profile_button);
        mProfilesOverlay = new FamilyProfilesOverlay(this, findViewById(R.id.family_root), profile -> {
            // A different profile reloads through the FamilyProfiles listener; the same one keeps what is loaded
            updateProfileButton();
            updateGreeting();
            if (!mRows.isEmpty()) {
                focusOnContent();
            } else {
                mProfileButton.requestFocus();
            }
        });
        mProfileButton.setOnClickListener(v -> mProfilesOverlay.show());
        mProfileButton.setOnFocusChangeListener((v, hasFocus) -> updateProfileButton());
        updateProfileButton();
        // Saving a profile (approved channels, age…) re-applies the rules to what's on screen
        FamilyProfiles.instance(this).addListener(() -> mHandler.post(this::onProfileChanged));

        mPresenter = BrowsePresenter.instance(this);
        mPresenter.setView(this);
        enableKidsSectionOnce();
        mPresenter.onViewInitialized();

        showLastCrashIfAny();
    }

    /** The engine hides YouTube Kids' home by default; we need it for kids profiles. */
    private void enableKidsSectionOnce() {
        android.content.SharedPreferences prefs = getSharedPreferences("family_ui", MODE_PRIVATE);
        if (!prefs.getBoolean("kids_section_enabled", false)) {
            mPresenter.enableSection(MediaGroup.TYPE_KIDS_HOME, true);
            prefs.edit().putBoolean("kids_section_enabled", true).apply();
        }
    }

    /** Kids see "Hola, Leo · quedan 35 min" instead of the app name. */
    private void updateGreeting() {
        TextView logo = findViewById(R.id.family_logo);
        FamilyProfile profile = currentProfile();
        if (!profile.isKid) {
            logo.setText(R.string.app_name);
            return;
        }
        int left = FamilyProfiles.instance(this).getMinutesLeft(profile);
        logo.setText("Hola, " + profile.name + (left >= 0 ? "  ·  quedan " + left + " min" : ""));
    }

    private FamilyProfile currentProfile() {
        return FamilyProfiles.instance(this).getCurrent();
    }

    /** Profile switched or its settings changed: reload what is shown with the new rules. */
    private void onProfileChanged() {
        if (isFinishing()) {
            return;
        }
        updateProfileButton();
        rebuildTabs();
        // Inicio depends on the profile (kids get YouTube Kids). Suscripciones and Biblioteca are the
        // same for every grown-up and are filtered when shown, so their preloaded copies stay.
        Snapshot subscriptions = mSectionCache.get(MediaGroup.TYPE_SUBSCRIPTIONS);
        Snapshot history = mSectionCache.get(MediaGroup.TYPE_HISTORY);
        mSectionCache.clear();
        if (subscriptions != null) {
            mSectionCache.put(MediaGroup.TYPE_SUBSCRIPTIONS, subscriptions);
        }
        if (history != null) {
            mSectionCache.put(MediaGroup.TYPE_HISTORY, history);
        }
        BrowseSection section = mTabs.isEmpty() ? null : findSection(mTabs.get(0).sectionId);
        mCurrentSection = null; // force a reload
        mPrefetchPending = true;
        if (section != null) {
            openSection(section, true);
        }
    }

    // ---------------------------------------------------------------- preloading

    private final List<io.reactivex.disposables.Disposable> mPrefetch = new ArrayList<>();
    private boolean mPrefetchPending = true;
    private int mPrefetchRunning;
    private Runnable mOnPrefetchDone;

    private void disposePrefetch() {
        for (io.reactivex.disposables.Disposable action : mPrefetch) {
            action.dispose();
        }
        mPrefetch.clear();
        mPrefetchRunning = 0;
    }

    /** Loads Suscripciones and Biblioteca in the background so they open instantly. */
    private void startPrefetch() {
        mPrefetchPending = false;
        if (mPrefetchRunning > 0) {
            return; // already on its way
        }
        com.liskovsoft.mediaserviceinterfaces.ContentService content =
                com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getContentService();
        prefetchGrid(MediaGroup.TYPE_SUBSCRIPTIONS, content.getSubscriptionsObserve());
        prefetchGrid(MediaGroup.TYPE_HISTORY, content.getHistoryObserve());
        checkPrefetchDone();
    }

    private void prefetchGrid(int sectionId, io.reactivex.Observable<MediaGroup> source) {
        BrowseSection section = findSection(sectionId);
        Snapshot cached = mSectionCache.get(sectionId);
        boolean fresh = cached != null && System.currentTimeMillis() - cached.timeMs < CACHE_MAX_AGE_MS;
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
                        // Keep everything except Shorts: each profile's rules are applied when it is shown
                        List<Video> videos = new ArrayList<>();
                        for (Video video : VideoGroup.from(mediaGroup, section).getVideos()) {
                            if (video != null && !video.isShorts) {
                                videos.add(video);
                            }
                        }
                        if (!videos.isEmpty()) {
                            mSectionCache.put(sectionId, new Snapshot(new ArrayList<>(), videos));
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

    /** If the app closed by itself last time, say why (details in Ajustes › Diagnóstico). */
    private void showLastCrashIfAny() {
        String problem = FamilyDiagnostics.instance(this).takePendingProblem();
        if (problem != null && !currentProfile().isKid) {
            FamilyDiagnosticsDialog.showProblem(this, problem);
        }
    }

    private void updateProfileButton() {
        FamilyAvatars.apply(mProfileButton, currentProfile(), mProfileButton.hasFocus(), 3);
    }

    @Override
    protected void initTheme() {
        setTheme(R.style.FamilyTheme);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPresenter != null) {
            mPresenter.setView(this);
            mPresenter.onViewResumed();
        }
        updateGreeting();
        // Something may have been watched meanwhile: history must be fresh
        mSectionCache.remove(MediaGroup.TYPE_HISTORY);
        if (sWelcomePending) {
            // Every time the app is opened (also from the background): welcome screen first,
            // then "¿Quién está viendo?"
            sWelcomePending = false;
            mSettingsOverlay.hide();
            mProfilesOverlay.hide();
            showWelcome();
        } else if (mWelcome.getVisibility() == View.VISIBLE) {
            // Still on the welcome screen: it moves on by itself
        } else if (FamilyProfiles.instance(this).shouldAskWhoIsWatching() && !mProfilesOverlay.isShowing()) {
            mSettingsOverlay.hide();
            // Mandatory: Back can't skip it (that would keep the last profile, maybe a grown-up's)
            mProfilesOverlay.showMandatory();
        } else if (mCurrentSection != null && FamilyProfiles.instance(this).isTimeUp(currentProfile())) {
            showTimeUp();
        }
    }

    // ---------------------------------------------------------------- welcome screen

    private static final long WELCOME_MIN_MS = 1800;
    private static final long WELCOME_MAX_MS = 6000;

    /** Logo while Suscripciones and Biblioteca load; at least a moment, never longer than a few seconds. */
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
        mWelcome.requestFocus();

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
            focusOnContent();
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (mWelcome != null && mWelcome.getVisibility() == View.VISIBLE) {
            // Nothing to do on the welcome screen; Back leaves the app
            if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                finish();
            }
            return true;
        }
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == android.view.KeyEvent.KEYCODE_DPAD_UP || key == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                mLastNavKeyMs = android.os.SystemClock.uptimeMillis();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** Something is drawn over the home screen (welcome, profiles, settings): don't move focus behind it. */
    private boolean isOverlayShowing() {
        return (mWelcome != null && mWelcome.getVisibility() == View.VISIBLE)
                || (mProfilesOverlay != null && mProfilesOverlay.isShowing())
                || (mSettingsOverlay != null && mSettingsOverlay.isShowing());
    }

    /** A kid used all of today's time: nothing more until an adult adds time with the PIN. */
    private void showTimeUp() {
        mRows.clear();
        mGridVideos.clear();
        mRowsAdapter.notifyDataSetChanged();
        setHero(null);
        showMessage("Se ha acabado el tiempo de hoy.\nMañana podrás ver más vídeos.", "Más tiempo (PIN)",
                () -> mProfilesOverlay.askPin(() -> {
                    FamilyProfiles.instance(this).grantExtraMinutes(currentProfile(), 30);
                    hideMessage();
                    onProfileChanged();
                }));
        mMessageAction.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        disposePrefetch();
        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mSettingsOverlay != null && mSettingsOverlay.onBack()) {
            return;
        }
        if (mProfilesOverlay != null && mProfilesOverlay.onBack()) {
            return;
        }
        if (mCurrentSection != null && mCurrentSection == mSettingsSection) {
            // Back from the advanced settings returns to our settings list
            openSection(findSection(mTabs.get(0).sectionId), false);
            mSettingsOverlay.show();
            return;
        }
        // From the videos, Back goes up to the tabs; from the tabs it exits as usual
        View focused = getCurrentFocus();
        if (focused != null && isInside(focused, mContent)) {
            TabInfo tab = findTab(mCurrentSection != null ? mCurrentSection.getId() : Integer.MIN_VALUE);
            if (tab != null) {
                tab.view.requestFocus();
                return;
            }
        }
        super.onBackPressed();
    }

    private static boolean isInside(View child, View parent) {
        View current = child;
        while (current != null) {
            if (current == parent) {
                return true;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    // ---------------------------------------------------------------- tabs

    private boolean isShortsSection(BrowseSection section) {
        return section.getId() == MediaGroup.TYPE_SHORTS || section.getType() == BrowseSection.TYPE_SHORTS_GRID;
    }

    /** Rebuilds the top tabs from the sections the engine offers. */
    private void rebuildTabs() {
        mTabsView.removeAllViews();
        mTabs.clear();
        mSettingsSection = null;
        mUpdateSection = null;

        boolean isKid = currentProfile().isKid;
        BrowseSection home = null;
        if (isKid) {
            // Kids get YouTube Kids' own home, not the grown-ups' one
            home = findSection(MediaGroup.TYPE_KIDS_HOME);
        }
        for (int i = 0; home == null && i < HOME_CANDIDATES.length; i++) {
            home = findSection(HOME_CANDIDATES[i]);
        }
        if (home != null) {
            addTab(home.getId(), "Inicio");
        }
        // Subscriptions and history are the grown-ups' ones (shared by the engine): not for kids
        if (!isKid && findSection(MediaGroup.TYPE_SUBSCRIPTIONS) != null) {
            addTab(MediaGroup.TYPE_SUBSCRIPTIONS, "Suscripciones");
        }
        if (!isKid && findSection(MediaGroup.TYPE_HISTORY) != null) {
            addTab(MediaGroup.TYPE_HISTORY, "Biblioteca");
        }
        addTab(TAB_SEARCH, "Buscar");
        updateGreeting();

        for (BrowseSection section : mSections) {
            if (section.getId() == MediaGroup.TYPE_SETTINGS) {
                mSettingsSection = section;
            } else if (section.getType() == BrowseSection.TYPE_ERROR && section.getData() instanceof ErrorFragmentData) {
                mUpdateSection = section; // e.g. "update found"
            }
        }

        if (mUpdateSection != null) {
            ErrorFragmentData data = (ErrorFragmentData) mUpdateSection.getData();
            String text = data.getActionText();
            mUpdateButton.setText(!TextUtils.isEmpty(text) ? text : "Actualizar");
            mUpdateButton.setVisibility(View.VISIBLE);
        } else {
            mUpdateButton.setVisibility(View.GONE);
        }
        // Kids can't open the settings
        boolean kid = currentProfile().isKid;
        mSettingsButton.setVisibility(mSettingsSection != null && !kid ? View.VISIBLE : View.GONE);
        if (kid) {
            mUpdateButton.setVisibility(View.GONE);
        }
        markCurrentTab();
    }

    private void addTab(int sectionId, String label) {
        TextView tab = (TextView) LayoutInflater.from(this).inflate(R.layout.family_tab, mTabsView, false);
        tab.setText(label);
        if (sectionId == TAB_SEARCH) {
            tab.setCompoundDrawablesWithIntrinsicBounds(R.drawable.family_ic_search, 0, 0, 0);
            tab.setCompoundDrawablePadding(getResources().getDimensionPixelSize(R.dimen.family_tab_icon_gap));
            tab.setOnClickListener(v -> SearchPresenter.instance(this).startSearch(null));
        } else {
            tab.setOnClickListener(v -> {
                BrowseSection section = findSection(sectionId);
                if (section != null) {
                    openSection(section, true);
                }
            });
            tab.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    return;
                }
                // Only when the user moved here with the remote: focus also lands on a tab by itself
                // (e.g. when a screen on top closes) and that must not change the section
                if (android.os.SystemClock.uptimeMillis() - mLastNavKeyMs > 700) {
                    return;
                }
                // Moving across tabs with the remote switches section after a short pause
                if (mPendingTabSwitch != null) {
                    mHandler.removeCallbacks(mPendingTabSwitch);
                }
                mPendingTabSwitch = () -> {
                    BrowseSection section = findSection(sectionId);
                    if (section != null && (mCurrentSection == null || mCurrentSection.getId() != sectionId)) {
                        openSection(section, false);
                    }
                };
                mHandler.postDelayed(mPendingTabSwitch, TAB_SWITCH_DELAY_MS);
            });
        }
        tab.setNextFocusDownId(R.id.family_content);
        mTabsView.addView(tab);
        mTabs.add(new TabInfo(sectionId, tab));
    }

    private void markCurrentTab() {
        int currentId = mCurrentSection != null ? mCurrentSection.getId() : Integer.MIN_VALUE;
        for (TabInfo tab : mTabs) {
            tab.view.setActivated(tab.sectionId == currentId);
        }
        mSettingsButton.setActivated(mSettingsSection != null && currentId == mSettingsSection.getId());
    }

    private TabInfo findTab(int sectionId) {
        for (TabInfo tab : mTabs) {
            if (tab.sectionId == sectionId) {
                return tab;
            }
        }
        return null;
    }

    private BrowseSection findSection(int sectionId) {
        for (BrowseSection section : mSections) {
            if (section.getId() == sectionId) {
                return section;
            }
        }
        return null;
    }

    private boolean isShownSection(BrowseSection section) {
        if (isShortsSection(section)) {
            return false;
        }
        return findTab(section.getId()) != null || section == mSettingsSection;
    }

    private void openSection(BrowseSection section, boolean focusOnContent) {
        FamilyProfile profile = currentProfile();
        if (section == mSettingsSection && profile.isKid) {
            return;
        }
        boolean changed = mCurrentSection == null || mCurrentSection.getId() != section.getId();
        if (changed) {
            saveToCache(mCurrentSection);
        }
        mCurrentSection = section;
        markCurrentTab();
        if (FamilyProfiles.instance(this).isTimeUp(profile)) {
            showTimeUp();
            return;
        }
        if (changed) {
            clearContent();
            mHero.setVisibility(section == mSettingsSection ? View.GONE : View.VISIBLE);
            if (restoreFromCache(section)) {
                // Instant: the engine just switches section without downloading it again
                mPresenter.onSectionFocusedNoReload(section.getId());
            } else {
                addKidChannelsRow(section);
                mLoadStartMs = System.currentTimeMillis();
                mPresenter.onSectionFocused(section.getId());
            }
        }
        if (focusOnContent) {
            focusOnContent();
        }
    }

    // ---------------------------------------------------------------- section cache

    private static final class Snapshot {
        final List<FamilyRow> rows;
        final List<Video> grid;
        final long timeMs;

        Snapshot(List<FamilyRow> rows, List<Video> grid) {
            this.rows = new ArrayList<>(rows);
            this.grid = new ArrayList<>(grid);
            this.timeMs = System.currentTimeMillis();
        }
    }

    private final java.util.Map<Integer, Snapshot> mSectionCache = new java.util.HashMap<>();

    private boolean isCacheable(BrowseSection section) {
        // Settings are tiny; history is dropped from the cache when coming back from a video (onResume)
        return section != null && section != mSettingsSection;
    }

    /** Keeps what the section shows, if it finished loading, to show it again instantly. */
    private void saveToCache(BrowseSection section) {
        if (!isCacheable(section) || mRows.isEmpty() || isProgressBarShowing() || mLoadStartMs > 0) {
            return;
        }
        mSectionCache.put(section.getId(), new Snapshot(mRows, mGridVideos));
    }

    private boolean restoreFromCache(BrowseSection section) {
        Snapshot snapshot = isCacheable(section) ? mSectionCache.get(section.getId()) : null;
        if (snapshot == null || System.currentTimeMillis() - snapshot.timeMs > CACHE_MAX_AGE_MS) {
            mSectionCache.remove(section.getId());
            return false;
        }
        if (isGridSection(section)) {
            // Grids are rebuilt from the videos with the current profile's rules
            mGridVideos.addAll(withoutShorts(snapshot.grid));
            if (mGridVideos.isEmpty()) {
                return false;
            }
            rebuildGridRows(0);
        } else {
            mRows.addAll(snapshot.rows);
            mRowsAdapter.notifyDataSetChanged();
        }
        return true;
    }

    // ---------------------------------------------------------------- content

    private void clearContent() {
        mRows.clear();
        mGridVideos.clear();
        mRowsAdapter.notifyDataSetChanged();
        hideMessage();
        setHero(null);
    }

    private boolean isGridSection(BrowseSection section) {
        int type = section.getType();
        return type == BrowseSection.TYPE_GRID || type == BrowseSection.TYPE_MULTI_GRID;
    }

    private boolean belongsToCurrent(BrowseSection section) {
        return section != null && mCurrentSection != null && section.getId() == mCurrentSection.getId();
    }

    /** Shorts are hidden for everyone; kids also go through the parental-control rules. */
    private List<Video> withoutShorts(List<Video> videos) {
        List<Video> result = new ArrayList<>();
        if (videos == null) {
            return result;
        }
        for (Video video : videos) {
            if (video != null && !video.isShorts && ContentGate.allows(video)) {
                result.add(video);
            }
        }
        return result;
    }

    /** On a kid's home, their approved channels come first as big buttons. */
    private void addKidChannelsRow(BrowseSection section) {
        FamilyProfile profile = currentProfile();
        if (!profile.isKid || profile.approvedChannels.isEmpty() || isGridSection(section)
                || findTab(section.getId()) == null || mTabs.get(0).sectionId != section.getId()) {
            return;
        }
        FamilyRow row = new FamilyRow(CHANNELS_ROW_ID, "Tus canales", mCardListener);
        for (java.util.Map.Entry<String, String> entry : profile.approvedChannels.entrySet()) {
            row.items.add(new FamilyChannelItem(entry.getKey(), entry.getValue()));
        }
        mRows.add(row);
        mRowsAdapter.notifyItemInserted(mRows.size() - 1);
    }

    private FamilyRow findRow(int id) {
        for (FamilyRow row : mRows) {
            if (row.id == id) {
                return row;
            }
        }
        return null;
    }

    private void applyRowGroup(VideoGroup group) {
        int action = group.getAction();
        FamilyRow row = findRow(group.getId());

        if (action == VideoGroup.ACTION_REMOVE || action == VideoGroup.ACTION_REMOVE_AUTHOR) {
            if (row != null) {
                for (Video video : group.getVideos()) {
                    removeVideo(row, video, action == VideoGroup.ACTION_REMOVE_AUTHOR);
                }
            }
            return;
        }

        if (action == VideoGroup.ACTION_REPLACE && group.getPosition() == -1) {
            // A full (re)load starts: empty the screen but keep the kid's channels on top
            clearContent();
            addKidChannelsRow(mCurrentSection);
            row = null;
        }

        List<Video> videos = withoutShorts(group.getVideos());
        if (videos.isEmpty() && row == null) {
            return;
        }

        if (row == null) {
            row = new FamilyRow(group.getId(), group.getTitle(), mCardListener);
            row.items.addAll(videos);
            int position = group.getPosition();
            if (position >= 0 && !mRows.isEmpty() && mRows.get(0).id == CHANNELS_ROW_ID) {
                position++; // engine positions don't count our "Tus canales" row
            }
            if (position < 0 || position > mRows.size()) {
                position = mRows.size();
            }
            mRows.add(position, row);
            mRowsAdapter.notifyItemInserted(position);
        } else if (action == VideoGroup.ACTION_REPLACE || action == VideoGroup.ACTION_SYNC) {
            row.items.clear();
            row.items.addAll(videos);
            row.adapter.notifyDataSetChanged();
        } else {
            int start = row.items.size();
            row.items.addAll(videos);
            row.adapter.notifyItemRangeInserted(start, videos.size());
        }
        onRowsArrived();
    }

    private void applyGridGroup(VideoGroup group) {
        int action = group.getAction();

        if (action == VideoGroup.ACTION_REMOVE || action == VideoGroup.ACTION_REMOVE_AUTHOR) {
            for (Video video : group.getVideos()) {
                for (int i = mGridVideos.size() - 1; i >= 0; i--) {
                    if (sameVideo(mGridVideos.get(i), video, action == VideoGroup.ACTION_REMOVE_AUTHOR)) {
                        mGridVideos.remove(i);
                    }
                }
            }
            rebuildGridRows(0);
            return;
        }

        if (action == VideoGroup.ACTION_REPLACE || action == VideoGroup.ACTION_SYNC) {
            mGridVideos.clear();
            mRows.clear();
            mRowsAdapter.notifyDataSetChanged();
        }

        int firstChangedRow = mGridVideos.size() / GRID_COLUMNS;
        mGridVideos.addAll(withoutShorts(group.getVideos()));
        rebuildGridRows(firstChangedRow);
        if (!mGridVideos.isEmpty()) {
            onRowsArrived();
        }
    }

    /** Re-chunks the grid videos into rows, touching only rows from firstChangedRow on. */
    private void rebuildGridRows(int firstChangedRow) {
        int rowCount = (mGridVideos.size() + GRID_COLUMNS - 1) / GRID_COLUMNS;
        int oldCount = mRows.size();
        for (int r = Math.min(firstChangedRow, oldCount); r < rowCount; r++) {
            FamilyRow row;
            if (r < mRows.size()) {
                row = mRows.get(r);
                row.items.clear();
            } else {
                String title = r == 0 && mCurrentSection != null ? mCurrentSection.getTitle() : null;
                row = new FamilyRow(GRID_ROW_ID_BASE - r, title, mCardListener);
                mRows.add(row);
            }
            int end = Math.min(mGridVideos.size(), (r + 1) * GRID_COLUMNS);
            row.items.addAll(mGridVideos.subList(r * GRID_COLUMNS, end));
            if (r < oldCount) {
                row.adapter.notifyDataSetChanged();
            }
        }
        while (mRows.size() > rowCount) {
            mRows.remove(mRows.size() - 1);
        }
        if (rowCount > oldCount) {
            mRowsAdapter.notifyItemRangeInserted(oldCount, rowCount - oldCount);
        } else if (rowCount < oldCount) {
            mRowsAdapter.notifyItemRangeRemoved(rowCount, oldCount - rowCount);
        }
    }

    private static boolean sameVideo(Video a, Video b, boolean byAuthor) {
        if (a == null || b == null) {
            return false;
        }
        if (byAuthor) {
            return a.channelId != null && a.channelId.equals(b.channelId);
        }
        return a.equals(b);
    }

    private void removeVideo(FamilyRow row, Video video, boolean byAuthor) {
        for (int i = row.items.size() - 1; i >= 0; i--) {
            Object item = row.items.get(i);
            if (item instanceof Video && sameVideo((Video) item, video, byAuthor)) {
                row.items.remove(i);
                row.adapter.notifyItemRemoved(i);
            }
        }
    }

    private final FamilyCardAdapter.Listener mCardListener = new FamilyCardAdapter.Listener() {
        @Override
        public void onCardFocused(FamilyRow row, Object item, int position) {
            if (!(item instanceof Video)) {
                return;
            }
            Video video = (Video) item;
            setHero(video);
            mPresenter.onVideoItemSelected(video);

            // Ask the engine for more when getting near the end
            if (row.id <= GRID_ROW_ID_BASE) {
                int rowIndex = mRows.indexOf(row);
                if (rowIndex >= mRows.size() - 2 && !mGridVideos.isEmpty()) {
                    mPresenter.onScrollEnd(mGridVideos.get(mGridVideos.size() - 1));
                }
            } else if (position > row.items.size() - SCROLL_CONTINUE_NUM) {
                Object last = row.items.get(row.items.size() - 1);
                if (last instanceof Video) {
                    mPresenter.onScrollEnd((Video) last);
                }
            }
        }

        @Override
        public void onCardClicked(Object item) {
            if (item instanceof Video) {
                mPresenter.onVideoItemClicked((Video) item);
            } else if (item instanceof SettingsItem && ((SettingsItem) item).onClick != null) {
                ((SettingsItem) item).onClick.run();
            } else if (item instanceof FamilyChannelItem) {
                ChannelPresenter.instance(FamilyBrowseActivity.this).openChannel(((FamilyChannelItem) item).channelId);
            }
        }

        @Override
        public void onCardLongClicked(Object item) {
            if (!(item instanceof Video)) {
                return;
            }
            Video video = (Video) item;
            if (currentProfile().isKid) {
                return; // no menus for kids
            }
            showVideoMenu(video);
        }
    };

    /** Adults: approve the channel for kids, or open the usual video menu. */
    private void showVideoMenu(Video video) {
        List<FamilyProfile> kids = new ArrayList<>();
        for (FamilyProfile profile : FamilyProfiles.instance(this).getAll()) {
            if (profile.isKid) {
                kids.add(profile);
            }
        }
        String channelName = !TextUtils.isEmpty(video.getAuthor()) ? video.getAuthor() : "este canal";
        if (kids.isEmpty() || TextUtils.isEmpty(video.channelId)) {
            mPresenter.onVideoItemLongClicked(video);
            return;
        }
        List<String> options = new ArrayList<>();
        for (FamilyProfile kid : kids) {
            options.add(kid.isChannelApproved(video.channelId)
                    ? "Quitar «" + channelName + "» de " + kid.name
                    : "Aprobar «" + channelName + "» para " + kid.name);
        }
        options.add("Más opciones del vídeo");
        new android.app.AlertDialog.Builder(this)
                .setTitle(channelName)
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    if (which >= kids.size()) {
                        mPresenter.onVideoItemLongClicked(video);
                        return;
                    }
                    FamilyProfile kid = kids.get(which);
                    if (kid.isChannelApproved(video.channelId)) {
                        kid.approvedChannels.remove(video.channelId);
                        android.widget.Toast.makeText(this, "Quitado de " + kid.name, android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        kid.approvedChannels.put(video.channelId, channelName);
                        android.widget.Toast.makeText(this, "Aprobado para " + kid.name, android.widget.Toast.LENGTH_SHORT).show();
                    }
                    FamilyProfiles.instance(this).update(kid);
                })
                .show();
    }

    private void setHero(Video video) {
        if (video == null) {
            if (mPendingHero != null) {
                mHandler.removeCallbacks(mPendingHero);
            }
            mHeroTitle.setText(null);
            mHeroMeta.setText(null);
            mHeroImageUrl = null;
            Glide.with(mHeroImage).clear(mHeroImage);
            mHeroImage.setImageDrawable(null);
            return;
        }
        mHeroTitle.setText(video.getTitle());
        CharSequence second = video.getSecondTitle();
        mHeroMeta.setText(!TextUtils.isEmpty(second) ? second : video.getAuthor());

        // The small card picture is enough for a faded background and uses far less memory.
        // Load it only once the remote stops on a card, not for every card it passes over.
        String url = video.getCardImageUrl();
        if (TextUtils.equals(url, mHeroImageUrl)) {
            return;
        }
        mHeroImageUrl = url;
        if (mPendingHero != null) {
            mHandler.removeCallbacks(mPendingHero);
        }
        mPendingHero = () -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (TextUtils.isEmpty(url)) {
                Glide.with(mHeroImage).clear(mHeroImage);
                mHeroImage.setImageDrawable(null);
            } else {
                Glide.with(mHeroImage)
                        .load(url)
                        .override(mHeroImage.getWidth() / 2 > 0 ? mHeroImage.getWidth() / 2 : 480,
                                mHeroImage.getHeight() / 2 > 0 ? mHeroImage.getHeight() / 2 : 270)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .centerCrop()
                        .dontAnimate()
                        .into(mHeroImage);
            }
        };
        mHandler.postDelayed(mPendingHero, 300);
    }

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

    // ---------------------------------------------------------------- BrowseView

    @Override
    public void addSection(int index, BrowseSection section) {
        if (section == null) {
            return;
        }
        // Every section is stored (also hidden ones like Shorts) so indexes match the presenter's
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
        if (target == null || !isShownSection(target)) {
            // The engine may start on a section we don't show (music, gaming…): open our home tab
            target = mTabs.isEmpty() ? null : findSection(mTabs.get(0).sectionId);
        }
        if (target != null) {
            openSection(target, focusOnContent);
        }
    }

    @Override
    public void updateSection(VideoGroup group) {
        // Apply on the next frame: changing lists while they are laying out crashes RecyclerView
        mPendingUpdates++;
        mHandler.post(() -> {
            mPendingUpdates--;
            try {
                updateSectionNow(group);
            } catch (IllegalStateException | IndexOutOfBoundsException e) {
                e.printStackTrace();
                mRowsAdapter.notifyDataSetChanged();
            }
        });
    }

    private void updateSectionNow(VideoGroup group) {
        if (isFinishing() || group == null || group.isShorts()) {
            return;
        }
        BrowseSection section = group.getSection();
        if (section != null && !belongsToCurrent(section)) {
            return;
        }
        if (mCurrentSection != null && isGridSection(mCurrentSection)) {
            applyGridGroup(group);
        } else {
            applyRowGroup(group);
        }
    }

    @Override
    public void updateSection(SettingsGroup group) {
        mHandler.post(() -> updateSettingsNow(group));
    }

    private void updateSettingsNow(SettingsGroup group) {
        if (isFinishing() || group == null || !belongsToCurrent(group.getCategory())) {
            return;
        }
        // Advanced (SmartTube) settings: several rows of cards so they all fit on screen
        mRows.clear();
        List<Object> items = new ArrayList<>(group.getItems());
        for (int start = 0, r = 0; start < items.size(); start += GRID_COLUMNS, r++) {
            FamilyRow row = new FamilyRow(GRID_ROW_ID_BASE + 100 + r, r == 0 ? "Ajustes avanzados" : null, mCardListener);
            row.items.addAll(items.subList(start, Math.min(items.size(), start + GRID_COLUMNS)));
            mRows.add(row);
        }
        mRowsAdapter.notifyDataSetChanged();
        onRowsArrived();
    }

    @Override
    public void clearSection(BrowseSection section) {
        // Same queue as the updates, so a clear never lands after newer rows
        mHandler.post(() -> {
            if (!isFinishing() && belongsToCurrent(section)) {
                clearContent();
            }
        });
    }

    @Override
    public void selectSectionItem(int index) {
        if (index >= 0 && !mRows.isEmpty()) {
            mContent.setSelectedPosition(0);
            FamilyRow row = mRows.get(0);
            if (index < row.items.size()) {
                row.selected = index;
                row.adapter.notifyItemChanged(index);
            }
        }
    }

    @Override
    public void selectSectionItem(Video item) {
        if (item == null) {
            return;
        }
        for (int r = 0; r < mRows.size(); r++) {
            FamilyRow row = mRows.get(r);
            int index = row.items.indexOf(item);
            if (index != -1) {
                row.selected = index;
                mContent.setSelectedPosition(r);
                mRowsAdapter.notifyItemChanged(r);
                return;
            }
        }
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
        mMessageAction.requestFocus();
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
        mHandler.post(() -> {
            if (isOverlayShowing()) {
                return; // don't steal focus from "¿Quién está viendo?"
            }
            if (!mRows.isEmpty()) {
                mFocusContentWhenLoaded = false;
                mContent.requestFocus();
            } else if (mMessageAction.getVisibility() == View.VISIBLE && mMessage.getVisibility() == View.VISIBLE) {
                mMessageAction.requestFocus();
            } else {
                mFocusContentWhenLoaded = true; // focus the first row as soon as it arrives
            }
        });
    }

    private void onRowsArrived() {
        hideMessage();
        if (mLoadStartMs > 0 && mCurrentSection != null) {
            FamilyDiagnostics.instance(this).log("«" + mCurrentSection.getTitle() + "» cargada en "
                    + (System.currentTimeMillis() - mLoadStartMs) + " ms");
            mLoadStartMs = 0;
            if (mPrefetchPending) {
                // Inicio is on screen: now fetch the other tabs (one moment later, so it isn't slowed down)
                mHandler.postDelayed(this::startPrefetch, 1500);
                mPrefetchPending = false;
            }
        }
        boolean overlayShowing = isOverlayShowing();
        if (mFocusContentWhenLoaded && !mRows.isEmpty() && !overlayShowing) {
            mFocusContentWhenLoaded = false;
            mHandler.post(() -> mContent.requestFocus());
        }
    }

    @Override
    public boolean isEmpty() {
        return mRows.isEmpty() && mPendingUpdates == 0;
    }

    @Override
    public void updateBadge() {
        // No badge in this design
    }
}
