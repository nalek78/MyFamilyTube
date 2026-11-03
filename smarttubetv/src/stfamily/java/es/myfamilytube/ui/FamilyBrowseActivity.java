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
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;

import java.util.ArrayList;
import java.util.List;

import es.myfamilytube.FamilyApplication;
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
    private static final int SCROLL_CONTINUE_NUM = 6;
    private static final long TAB_SWITCH_DELAY_MS = 350;

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
    private TextView mProfileButton;
    private FamilyProfilesOverlay mProfilesOverlay;

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

        mRowsAdapter = new FamilyRowsAdapter(mRows);
        mContent.setAdapter(mRowsAdapter);
        mContent.setItemSpacing(getResources().getDimensionPixelSize(R.dimen.family_card_gap));
        mContent.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_LOW_EDGE);
        mContent.setWindowAlignmentOffsetPercent(VerticalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);
        mContent.setItemAlignmentOffsetPercent(VerticalGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED);
        // Up from the first row reaching the tabs: lb:focusOutFront="true" in family_browse.xml

        mSettingsButton.setOnClickListener(v -> {
            if (mSettingsSection != null) {
                openSection(mSettingsSection, true);
            }
        });
        mUpdateButton.setOnClickListener(v -> {
            if (mUpdateSection != null && mUpdateSection.getData() instanceof ErrorFragmentData) {
                ((ErrorFragmentData) mUpdateSection.getData()).onAction();
            }
        });

        mProfileButton = findViewById(R.id.family_profile_button);
        mProfilesOverlay = new FamilyProfilesOverlay(this, findViewById(R.id.family_root), profile -> {
            updateProfileButton();
            mProfileButton.requestFocus();
        });
        mProfileButton.setOnClickListener(v -> mProfilesOverlay.show());
        mProfileButton.setOnFocusChangeListener((v, hasFocus) -> updateProfileButton());
        updateProfileButton();

        mPresenter = BrowsePresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();

        if (FamilyProfiles.instance(this).shouldAskWhoIsWatching()) {
            mProfilesOverlay.show();
        }

        showLastCrashIfAny();
    }

    /** If the app closed because of an error last time, show it so it can be reported. */
    private void showLastCrashIfAny() {
        String crash = FamilyApplication.takeLastCrash(this);
        if (crash == null) {
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("La app se cerró por un error")
                .setMessage("Haz una foto a este mensaje y pásasela a quien te ayuda con la app:\n\n" + crash)
                .setPositiveButton("Cerrar", null)
                .show();
    }

    private void updateProfileButton() {
        FamilyProfile profile = FamilyProfiles.instance(this).getCurrent();
        mProfileButton.setText(profile.getInitial());
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(profile.color);
        if (mProfileButton.hasFocus()) {
            circle.setStroke((int) (3 * getResources().getDisplayMetrics().density), 0xFFFF3D33);
        }
        mProfileButton.setBackground(circle);
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
        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mProfilesOverlay != null && mProfilesOverlay.onBack()) {
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

        BrowseSection home = null;
        for (int candidate : HOME_CANDIDATES) {
            home = findSection(candidate);
            if (home != null) {
                break;
            }
        }
        if (home != null) {
            addTab(home.getId(), "Inicio");
        }
        if (findSection(MediaGroup.TYPE_SUBSCRIPTIONS) != null) {
            addTab(MediaGroup.TYPE_SUBSCRIPTIONS, "Suscripciones");
        }
        if (findSection(MediaGroup.TYPE_HISTORY) != null) {
            addTab(MediaGroup.TYPE_HISTORY, "Biblioteca");
        }
        addTab(TAB_SEARCH, "Buscar");

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
        mSettingsButton.setVisibility(mSettingsSection != null ? View.VISIBLE : View.GONE);
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
        boolean changed = mCurrentSection == null || mCurrentSection.getId() != section.getId();
        mCurrentSection = section;
        markCurrentTab();
        if (changed) {
            clearContent();
            mHero.setVisibility(section == mSettingsSection ? View.GONE : View.VISIBLE);
            mPresenter.onSectionFocused(section.getId());
        }
        if (focusOnContent) {
            focusOnContent();
        }
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

    private List<Video> withoutShorts(List<Video> videos) {
        List<Video> result = new ArrayList<>();
        if (videos == null) {
            return result;
        }
        for (Video video : videos) {
            if (video != null && !video.isShorts) {
                result.add(video);
            }
        }
        return result;
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
            clearContent();
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
            }
        }

        @Override
        public void onCardLongClicked(Object item) {
            if (item instanceof Video) {
                mPresenter.onVideoItemLongClicked((Video) item);
            }
        }
    };

    private void setHero(Video video) {
        if (video == null) {
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

        String url = video.getBackgroundUrl();
        if (TextUtils.isEmpty(url)) {
            url = video.getCardImageUrl();
        }
        if (!TextUtils.equals(url, mHeroImageUrl)) {
            mHeroImageUrl = url;
            if (TextUtils.isEmpty(url)) {
                Glide.with(mHeroImage).clear(mHeroImage);
                mHeroImage.setImageDrawable(null);
            } else {
                Glide.with(mHeroImage).load(url).centerCrop().into(mHeroImage);
            }
        }
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
        mRows.clear();
        FamilyRow row = new FamilyRow(GRID_ROW_ID_BASE + 1, "Ajustes", mCardListener);
        row.items.addAll(group.getItems());
        mRows.add(row);
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
            if (mProfilesOverlay != null && mProfilesOverlay.isShowing()) {
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
        boolean overlayShowing = mProfilesOverlay != null && mProfilesOverlay.isShowing();
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
