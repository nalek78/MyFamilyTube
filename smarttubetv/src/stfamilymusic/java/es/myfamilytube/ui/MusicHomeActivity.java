package es.myfamilytube.ui;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import es.myfamilytube.auto.CarMusicService;
import es.myfamilytube.profiles.FamilyAccounts;
import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;

/**
 * My Family Music on the phone, like a music app: tabs, lists, search, a mini player and a
 * full player. It shows exactly the same music as in the car (it talks to the same service),
 * so the saved songs, the even volume and the parental control work here too.
 */
public class MusicHomeActivity extends MotherActivity {
    private static final String ROOT = "root";
    private static final String GROUP_HINT = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT";
    private static final int REQUEST_VOICE = 4901;
    private static final int ACCENT = 0xFF19D9A0;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private FamilyProfilesOverlay mProfiles;
    private MediaBrowserCompat mBrowser;
    private MediaControllerCompat mController;

    private TextView mAvatar;
    private TextView mTitle;
    private View mBack;
    private View mLogo;
    private View mTop;
    private View mSearchBar;
    private EditText mSearchField;
    private View mTabsScroll;
    private LinearLayout mTabs;
    private RecyclerView mList;
    private View mProgress;
    private TextView mEmpty;
    private View mMini;
    private ImageView mMiniArt;
    private TextView mMiniTitle;
    private TextView mMiniArtist;
    private ImageView mMiniPlay;
    private View mPlayer;
    private ImageView mPlayerArt;
    private TextView mPlayerTitle;
    private TextView mPlayerArtist;
    private SeekBar mPlayerSeek;
    private TextView mPlayerTime;
    private TextView mPlayerDuration;
    private ImageView mPlayerPlay;
    private boolean mSeeking;

    private final MusicAdapter mAdapter = new MusicAdapter();
    private final List<MediaItem> mRootTabs = new ArrayList<>();
    /** Where we are: the tab, then the lists or channels opened inside it. */
    private final List<String[]> mStack = new ArrayList<>(); // {mediaId, title}
    private String mSubscribed;
    private String mArtUri;
    private String mLastError;

    @Override
    protected void initTheme() {
        setTheme(R.style.MobileTheme);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.music_home);
        findViews();

        ViewGroup root = findViewById(R.id.music_root);
        mProfiles = new FamilyProfilesOverlay(this, root, profile -> showProfile());
        mAvatar.setOnClickListener(v -> mProfiles.show());
        findViewById(R.id.music_more).setOnClickListener(v -> showMoreMenu());
        mBack.setOnClickListener(v -> onBackPressed());
        findViewById(R.id.music_search).setOnClickListener(v -> openSearch());
        findViewById(R.id.music_search_close).setOnClickListener(v -> closeSearch());
        findViewById(R.id.music_mic).setOnClickListener(v -> startVoiceSearch());
        mSearchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search(mSearchField.getText().toString());
                return true;
            }
            return false;
        });

        mList.setLayoutManager(new LinearLayoutManager(this));
        mList.setAdapter(mAdapter);
        setupPlayer();

        FamilyProfiles.instance(this).addListener(() -> mHandler.post(() -> {
            if (!isFinishing()) {
                FamilyAccounts.applyFor(this, FamilyProfiles.instance(this).getCurrent());
                showProfile();
            }
        }));
        showProfile();

        mBrowser = new MediaBrowserCompat(this, new ComponentName(this, CarMusicService.class), mConnection, null);
        mBrowser.connect();
        // Android 13+: without it the controls with the screen off don't show (the music still plays)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.content.SharedPreferences prefs = getSharedPreferences("family_ui", MODE_PRIVATE);
            if (!prefs.getBoolean("notifications_asked", false)
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("notifications_asked", true).apply();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 4902);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mProfiles.onResumed(); // back from adding a Google account?
        showProfile();
        if (FamilyProfiles.instance(this).shouldAskWhoIsWatching() && !mProfiles.isShowing()) {
            mProfiles.showMandatory();
        }
        mHandler.postDelayed(() -> MobileUpdates.checkOnce(this), 2000);
        mHandler.post(mTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTicker);
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        if (mController != null) {
            mController.unregisterCallback(mControllerCallback);
        }
        if (mBrowser != null) {
            mBrowser.disconnect(); // the music keeps playing (the service stays in the foreground)
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mProfiles.onBack()) {
            return;
        }
        if (mPlayer.getVisibility() == View.VISIBLE) {
            mPlayer.setVisibility(View.GONE);
            return;
        }
        if (mSearchBar.getVisibility() == View.VISIBLE) {
            closeSearch();
            return;
        }
        if (mStack.size() > 1) {
            mStack.remove(mStack.size() - 1);
            String[] top = mStack.get(mStack.size() - 1);
            load(top[0]);
            return;
        }
        super.onBackPressed();
    }

    private void findViews() {
        mAvatar = findViewById(R.id.family_profile_button);
        mTitle = findViewById(R.id.music_title);
        mBack = findViewById(R.id.music_back);
        mLogo = findViewById(R.id.music_logo);
        mTop = findViewById(R.id.music_top);
        mSearchBar = findViewById(R.id.music_search_bar);
        mSearchField = findViewById(R.id.music_search_field);
        mTabsScroll = findViewById(R.id.music_tabs_scroll);
        mTabs = findViewById(R.id.music_tabs);
        mList = findViewById(R.id.music_list);
        mProgress = findViewById(R.id.music_progress);
        mEmpty = findViewById(R.id.music_empty);
        mMini = findViewById(R.id.music_mini);
        mMiniArt = findViewById(R.id.music_mini_art);
        mMiniTitle = findViewById(R.id.music_mini_title);
        mMiniArtist = findViewById(R.id.music_mini_artist);
        mMiniPlay = findViewById(R.id.music_mini_play);
        mPlayer = findViewById(R.id.music_player);
        mPlayerArt = findViewById(R.id.music_player_art);
        mPlayerTitle = findViewById(R.id.music_player_title);
        mPlayerArtist = findViewById(R.id.music_player_artist);
        mPlayerSeek = findViewById(R.id.music_player_seek);
        mPlayerTime = findViewById(R.id.music_player_time);
        mPlayerDuration = findViewById(R.id.music_player_duration);
        mPlayerPlay = findViewById(R.id.music_player_play);
    }

    // ---------------------------------------------------------------- profile and menu

    private void showProfile() {
        FamilyProfile profile = FamilyProfiles.instance(this).getCurrent();
        FamilyAvatars.apply(mAvatar, profile, false, 0);
    }

    private void showMoreMenu() {
        FamilyProfile profile = FamilyProfiles.instance(this).getCurrent();
        String account = !TextUtils.isEmpty(FamilyAccounts.currentKey()) ? "con su cuenta de Google" : "sin cuenta de Google";
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            // no version
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name) + " " + version)
                .setMessage("Escucha: " + profile.name + (profile.isKid ? " (infantil)" : ", " + account)
                        + "\n\nEn el coche: conecta el móvil y abre " + getString(R.string.app_name)
                        + " en Android Auto. Las canciones siguientes se guardan solas en el móvil para que no se corte sin cobertura.")
                .setPositiveButton("Cambiar perfil", (dialog, which) -> mProfiles.show())
                .setNeutralButton("Diagnóstico", (dialog, which) -> FamilyDiagnosticsDialog.show(this))
                .setNegativeButton("Cerrar", null)
                .show();
    }

    // ---------------------------------------------------------------- talking to the music service

    private final MediaBrowserCompat.ConnectionCallback mConnection = new MediaBrowserCompat.ConnectionCallback() {
        @Override
        public void onConnected() {
            try {
                mController = new MediaControllerCompat(MusicHomeActivity.this, mBrowser.getSessionToken());
                mController.registerCallback(mControllerCallback);
                showPlayback();
            } catch (Exception e) {
                // without controls the lists still work
            }
            mBrowser.subscribe(ROOT, mRootCallback);
        }

        @Override
        public void onConnectionFailed() {
            showMessage("No se ha podido abrir la música. Cierra la app y vuelve a abrirla.");
        }
    };

    /** The tabs (Inicio, Tus listas…); they change with the profile. */
    private final MediaBrowserCompat.SubscriptionCallback mRootCallback = new MediaBrowserCompat.SubscriptionCallback() {
        @Override
        public void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaItem> children) {
            mRootTabs.clear();
            mRootTabs.addAll(children);
            String current = mStack.isEmpty() ? null : mStack.get(0)[0];
            boolean stillThere = false;
            for (MediaItem tab : children) {
                if (tab.getMediaId() != null && tab.getMediaId().equals(current)) {
                    stillThere = true;
                }
            }
            drawTabs();
            if (!stillThere && !children.isEmpty()) {
                openTab(children.get(0));
            } else if (stillThere) {
                // Same tab, but another profile may see other things: load it again
                String[] top = mStack.get(mStack.size() - 1);
                load(top[0]);
            }
        }
    };

    private final MediaBrowserCompat.SubscriptionCallback mListCallback = new MediaBrowserCompat.SubscriptionCallback() {
        @Override
        public void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaItem> children) {
            if (parentId.equals(mSubscribed)) {
                showItems(children, "No hay nada aquí todavía.");
            }
        }

        @Override
        public void onError(@NonNull String parentId) {
            if (parentId.equals(mSubscribed)) {
                showItems(new ArrayList<>(), "No se ha podido cargar. Comprueba la conexión.");
            }
        }
    };

    private void drawTabs() {
        mTabs.removeAllViews();
        String current = mStack.isEmpty() ? null : mStack.get(0)[0];
        LayoutInflater inflater = LayoutInflater.from(this);
        for (MediaItem tab : mRootTabs) {
            TextView chip = (TextView) inflater.inflate(R.layout.music_tab, mTabs, false);
            chip.setText(tab.getDescription().getTitle());
            chip.setActivated(tab.getMediaId() != null && tab.getMediaId().equals(current));
            chip.setOnClickListener(v -> openTab(tab));
            mTabs.addView(chip);
        }
    }

    private void openTab(MediaItem tab) {
        mStack.clear();
        mStack.add(new String[]{tab.getMediaId(), String.valueOf(tab.getDescription().getTitle())});
        drawTabs();
        load(tab.getMediaId());
    }

    private void load(String mediaId) {
        if (mBrowser == null || !mBrowser.isConnected()) {
            return;
        }
        if (mSubscribed != null) {
            mBrowser.unsubscribe(mSubscribed);
        }
        mSubscribed = mediaId;
        updateHeader();
        mAdapter.setItems(new ArrayList<>());
        mEmpty.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
        mBrowser.subscribe(mediaId, mListCallback);
    }

    private void updateHeader() {
        boolean inside = mStack.size() > 1;
        mBack.setVisibility(inside ? View.VISIBLE : View.GONE);
        mLogo.setVisibility(inside ? View.GONE : View.VISIBLE);
        mTitle.setText(inside ? mStack.get(mStack.size() - 1)[1] : getString(R.string.app_name));
    }

    private void showItems(List<MediaItem> children, String emptyText) {
        mProgress.setVisibility(View.GONE);
        List<Object> rows = new ArrayList<>();
        String lastGroup = null;
        for (MediaItem item : children) {
            Bundle extras = item.getDescription().getExtras();
            String group = extras != null ? extras.getString(GROUP_HINT) : null;
            if (group != null && !group.equals(lastGroup)) {
                rows.add(group);
            }
            lastGroup = group;
            rows.add(item);
        }
        mAdapter.setItems(rows);
        mList.scrollToPosition(0);
        if (rows.isEmpty()) {
            showMessage(emptyText);
        } else {
            mEmpty.setVisibility(View.GONE);
        }
    }

    private void showMessage(String text) {
        mProgress.setVisibility(View.GONE);
        mEmpty.setText(text);
        mEmpty.setVisibility(View.VISIBLE);
    }

    private void onItemClicked(MediaItem item) {
        if (item.isBrowsable()) {
            mStack.add(new String[]{item.getMediaId(), String.valueOf(item.getDescription().getTitle())});
            load(item.getMediaId());
        } else if (item.isPlayable() && mController != null) {
            mController.getTransportControls().playFromMediaId(item.getMediaId(), null);
            mPlayer.setVisibility(View.VISIBLE);
        }
    }

    // ---------------------------------------------------------------- search

    private void openSearch() {
        mTop.setVisibility(View.GONE);
        mSearchBar.setVisibility(View.VISIBLE);
        mSearchField.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            mSearchField.post(() -> imm.showSoftInput(mSearchField, InputMethodManager.SHOW_IMPLICIT));
        }
    }

    private void closeSearch() {
        hideKeyboard();
        mSearchBar.setVisibility(View.GONE);
        mTop.setVisibility(View.VISIBLE);
        mTabsScroll.setVisibility(View.VISIBLE);
        if (!mStack.isEmpty()) {
            load(mStack.get(mStack.size() - 1)[0]);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mSearchField.getWindowToken(), 0);
        }
        mSearchField.clearFocus();
    }

    private void search(String query) {
        if (TextUtils.isEmpty(query) || TextUtils.isEmpty(query.trim()) || mBrowser == null || !mBrowser.isConnected()) {
            return;
        }
        hideKeyboard();
        if (mSubscribed != null) {
            mBrowser.unsubscribe(mSubscribed);
            mSubscribed = null;
        }
        mTabsScroll.setVisibility(View.GONE);
        mAdapter.setItems(new ArrayList<>());
        mEmpty.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
        mBrowser.search(query.trim(), null, new MediaBrowserCompat.SearchCallback() {
            @Override
            public void onSearchResult(@NonNull String q, Bundle extras, @NonNull List<MediaItem> items) {
                showItems(items, "No se ha encontrado nada.");
            }

            @Override
            public void onError(@NonNull String q, Bundle extras) {
                showItems(new ArrayList<>(), "No se ha podido buscar. Comprueba la conexión.");
            }
        });
    }

    private void startVoiceSearch() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "¿Qué quieres escuchar?");
        try {
            startActivityForResult(intent, REQUEST_VOICE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Este móvil no tiene búsqueda por voz", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                mSearchField.setText(results.get(0));
                search(results.get(0));
            }
        }
    }

    // ---------------------------------------------------------------- player

    private void setupPlayer() {
        mMini.setOnClickListener(v -> mPlayer.setVisibility(View.VISIBLE));
        mMiniPlay.setOnClickListener(v -> togglePlay());
        findViewById(R.id.music_mini_next).setOnClickListener(v -> {
            if (mController != null) {
                mController.getTransportControls().skipToNext();
            }
        });
        findViewById(R.id.music_player_close).setOnClickListener(v -> mPlayer.setVisibility(View.GONE));
        mPlayerPlay.setOnClickListener(v -> togglePlay());
        findViewById(R.id.music_player_next).setOnClickListener(v -> {
            if (mController != null) {
                mController.getTransportControls().skipToNext();
            }
        });
        findViewById(R.id.music_player_prev).setOnClickListener(v -> {
            if (mController != null) {
                mController.getTransportControls().skipToPrevious();
            }
        });
        mPlayerSeek.setMax(1000);
        mPlayerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mPlayerTime.setText(formatTime(progress * duration() / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mSeeking = false;
                long duration = duration();
                if (mController != null && duration > 0) {
                    mController.getTransportControls().seekTo(seekBar.getProgress() * duration / 1000);
                }
            }
        });
        // Square picture, as wide as the screen allows
        mPlayerArt.post(() -> {
            ViewGroup.LayoutParams params = mPlayerArt.getLayoutParams();
            params.height = Math.min(mPlayerArt.getWidth(), (int) (getResources().getDisplayMetrics().heightPixels * 0.45f));
            mPlayerArt.setLayoutParams(params);
        });
    }

    private void togglePlay() {
        if (mController == null) {
            return;
        }
        PlaybackStateCompat state = mController.getPlaybackState();
        if (state != null && (state.getState() == PlaybackStateCompat.STATE_PLAYING
                || state.getState() == PlaybackStateCompat.STATE_BUFFERING)) {
            mController.getTransportControls().pause();
        } else {
            mController.getTransportControls().play();
        }
    }

    private final MediaControllerCompat.Callback mControllerCallback = new MediaControllerCompat.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            showPlayback();
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            showPlayback();
        }
    };

    private void showPlayback() {
        if (mController == null) {
            return;
        }
        MediaMetadataCompat metadata = mController.getMetadata();
        PlaybackStateCompat state = mController.getPlaybackState();
        boolean hasSong = metadata != null && !TextUtils.isEmpty(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
        mMini.setVisibility(hasSong ? View.VISIBLE : View.GONE);
        String error = state != null && state.getState() == PlaybackStateCompat.STATE_ERROR && state.getErrorMessage() != null
                ? state.getErrorMessage().toString() : null;
        if (error != null && !error.equals(mLastError)) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        }
        mLastError = error;
        if (!hasSong) {
            return;
        }
        String title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
        mMiniTitle.setText(title);
        mMiniArtist.setText(artist);
        mPlayerTitle.setText(title);
        mPlayerArtist.setText(artist);
        String art = metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI);
        if (art != null && !art.equals(mArtUri)) {
            mArtUri = art;
            Glide.with(mMiniArt).load(Uri.parse(art)).centerCrop().into(mMiniArt);
            Glide.with(mPlayerArt).load(Uri.parse(art)).centerCrop().into(mPlayerArt);
        }
        boolean playing = state != null && (state.getState() == PlaybackStateCompat.STATE_PLAYING
                || state.getState() == PlaybackStateCompat.STATE_BUFFERING);
        int icon = playing ? R.drawable.mobile_ic_pause : R.drawable.mobile_ic_play;
        mMiniPlay.setImageResource(icon);
        mPlayerPlay.setImageResource(icon);
        updateProgress();
    }

    private long duration() {
        MediaMetadataCompat metadata = mController != null ? mController.getMetadata() : null;
        return metadata != null ? metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) : 0;
    }

    private long position() {
        PlaybackStateCompat state = mController != null ? mController.getPlaybackState() : null;
        if (state == null) {
            return 0;
        }
        long position = state.getPosition();
        if (state.getState() == PlaybackStateCompat.STATE_PLAYING) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        long duration = duration();
        return duration > 0 ? Math.min(position, duration) : position;
    }

    private void updateProgress() {
        if (mSeeking) {
            return;
        }
        long duration = duration();
        long position = position();
        mPlayerTime.setText(formatTime(position));
        mPlayerDuration.setText(duration > 0 ? formatTime(duration) : "");
        mPlayerSeek.setProgress(duration > 0 ? (int) (position * 1000 / duration) : 0);
    }

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            if (mPlayer.getVisibility() == View.VISIBLE) {
                updateProgress();
            }
            mHandler.postDelayed(this, 500);
        }
    };

    private static String formatTime(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    // ---------------------------------------------------------------- list

    private final class MusicAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_ITEM = 1;
        private final List<Object> mItems = new ArrayList<>();

        void setItems(List<Object> items) {
            mItems.clear();
            mItems.addAll(items);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @Override
        public int getItemViewType(int position) {
            return mItems.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View view = inflater.inflate(viewType == TYPE_HEADER ? R.layout.mobile_header : R.layout.music_row, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = mItems.get(position);
            if (item instanceof String) {
                ((TextView) holder.itemView).setText((String) item);
                return;
            }
            MediaItem media = (MediaItem) item;
            View view = holder.itemView;
            ((TextView) view.findViewById(R.id.music_row_title)).setText(media.getDescription().getTitle());
            TextView subtitle = view.findViewById(R.id.music_row_subtitle);
            CharSequence sub = media.getDescription().getSubtitle();
            subtitle.setText(sub);
            subtitle.setVisibility(TextUtils.isEmpty(sub) ? View.GONE : View.VISIBLE);
            view.findViewById(R.id.music_row_more).setVisibility(media.isBrowsable() ? View.VISIBLE : View.GONE);
            ImageView art = view.findViewById(R.id.music_row_art);
            Uri icon = media.getDescription().getIconUri();
            if (icon != null) {
                Glide.with(art).load(icon).centerCrop().into(art);
            } else {
                Glide.with(art).clear(art);
                art.setImageDrawable(null);
            }
            view.setOnClickListener(v -> onItemClicked(media));
        }
    }
}
