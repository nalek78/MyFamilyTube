package es.myfamilytube.ui;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
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
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import es.myfamilytube.auto.CarMusicService;
import es.myfamilytube.profiles.FamilyAccounts;
import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;

/**
 * My Family Music on the phone, in the style of Spotify and YouTube Music: a greeting, tabs chosen
 * from what the profile listens to, shelves of covers, a floating mini player and a full player
 * coloured after the cover, with the queue («A continuación»). It shows exactly the same music as
 * in the car (it talks to the same service), so the saved songs, the even volume and the
 * parental control work here too.
 */
public class MusicHomeActivity extends MotherActivity {
    private static final String ROOT = "root";
    private static final String HIDDEN_LISTS = "hidden:lists";
    private static final String GROUP_HINT = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT";
    private static final int REQUEST_VOICE = 4901;
    private static final int BG = 0xFF101114;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private FamilyProfilesOverlay mProfiles;
    private MediaBrowserCompat mBrowser;
    private MediaControllerCompat mController;

    private TextView mAvatar;
    private TextView mTitle;
    private View mBack;
    private View mTop;
    private View mGlow;
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
    private View mMiniProgress;
    private View mPlayer;
    private View mPlayerArtBox;
    private ImageView mPlayerArt;
    private TextView mPlayerTitle;
    private TextView mPlayerArtist;
    private TextView mPlayerAlbum;
    private TextView mPlayerSource;
    private SeekBar mPlayerSeek;
    private TextView mPlayerTime;
    private TextView mPlayerDuration;
    private ImageView mPlayerPlay;
    private View mQueue;
    private boolean mSeeking;

    private final MusicAdapter mAdapter = new MusicAdapter();
    private final QueueAdapter mQueueAdapter = new QueueAdapter();
    private final List<MediaItem> mRootTabs = new ArrayList<>();
    /** Where we are: the tab, then what was opened inside it. */
    private final List<Place> mStack = new ArrayList<>();
    private String mSubscribed;
    private boolean mSearching;
    private String mArtUri;
    private Object mArtSource;
    /** New iTunes covers found: draw the lists and the player again (once, not for each cover). */
    private final Runnable mRedrawCovers = () -> {
        mAdapter.notifyDataSetChanged();
        mQueueAdapter.notifyDataSetChanged();
        showPlayback();
    };
    private String mLastError;
    /** The video playing now, to mark it in the lists. */
    private String mPlayingId;
    private String mSource = "";

    private static final class Place {
        final String mediaId;
        final String title;
        final String subtitle;
        final Uri icon;

        Place(String mediaId, CharSequence title, CharSequence subtitle, Uri icon) {
            this.mediaId = mediaId;
            this.title = title != null ? title.toString() : "";
            this.subtitle = subtitle != null ? subtitle.toString() : null;
            this.icon = icon;
        }
    }

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

        MusicArtLoader.setOnFound(() -> {
            mHandler.removeCallbacks(mRedrawCovers);
            mHandler.postDelayed(mRedrawCovers, 600);
        });
        mList.setLayoutManager(new LinearLayoutManager(this));
        mList.setAdapter(mAdapter);
        mList.setItemViewCacheSize(8);
        RecyclerView queueList = findViewById(R.id.music_queue_list);
        queueList.setLayoutManager(new LinearLayoutManager(this));
        queueList.setAdapter(mQueueAdapter);
        setupPlayer();
        applyColor(0xFF2A3B36);

        FamilyProfiles.instance(this).addListener(() -> mHandler.post(() -> {
            if (!isFinishing()) {
                FamilyAccounts.applyFor(this, FamilyProfiles.instance(this).getCurrent());
                showProfile();
            }
        }));
        showProfile();
        updateHeader();

        // Closed by an error last time? Say what it was (also saved in Diagnóstico)
        String problem = es.myfamilytube.FamilyDiagnostics.instance(this).takePendingProblem();
        if (problem != null && !FamilyProfiles.instance(this).getCurrent().isKid) {
            FamilyDiagnosticsDialog.showProblem(this, problem);
        }

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
        updateHeader();
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
        MusicArtLoader.setOnFound(null);
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
        if (mQueue.getVisibility() == View.VISIBLE) {
            mQueue.setVisibility(View.GONE);
            return;
        }
        if (mPlayer.getVisibility() == View.VISIBLE) {
            hidePlayer();
            return;
        }
        if (mSearchBar.getVisibility() == View.VISIBLE) {
            closeSearch();
            return;
        }
        if (mStack.size() > 1) {
            mStack.remove(mStack.size() - 1);
            load(mStack.get(mStack.size() - 1).mediaId);
            return;
        }
        super.onBackPressed();
    }

    private void findViews() {
        mAvatar = findViewById(R.id.family_profile_button);
        mTitle = findViewById(R.id.music_title);
        mBack = findViewById(R.id.music_back);
        mTop = findViewById(R.id.music_top);
        mGlow = findViewById(R.id.music_glow);
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
        mMiniProgress = findViewById(R.id.music_mini_progress);
        mPlayer = findViewById(R.id.music_player);
        mPlayerArtBox = findViewById(R.id.music_player_art_box);
        mPlayerArt = findViewById(R.id.music_player_art);
        mPlayerTitle = findViewById(R.id.music_player_title);
        mPlayerArtist = findViewById(R.id.music_player_artist);
        mPlayerAlbum = findViewById(R.id.music_player_album);
        mPlayerSource = findViewById(R.id.music_player_source);
        mPlayerSeek = findViewById(R.id.music_player_seek);
        mPlayerTime = findViewById(R.id.music_player_time);
        mPlayerDuration = findViewById(R.id.music_player_duration);
        mPlayerPlay = findViewById(R.id.music_player_play);
        mQueue = findViewById(R.id.music_queue);
        clipToRoundedBackground(mMiniArt);
        clipToRoundedBackground(mPlayerArt);
    }

    private static void clipToRoundedBackground(View view) {
        view.setClipToOutline(true); // the picture takes the rounded corners of its background
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
                        + "\n\nLas pestañas se eligen según lo que escuchas: tus artistas, estilos y álbumes favoritos."
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
                showQueue();
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

    /** The tabs; they change with the profile and with what it listens to. */
    private final MediaBrowserCompat.SubscriptionCallback mRootCallback = new MediaBrowserCompat.SubscriptionCallback() {
        @Override
        public void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaItem> children) {
            mRootTabs.clear();
            mRootTabs.addAll(children);
            String current = mStack.isEmpty() ? null : mStack.get(0).mediaId;
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
                load(mStack.get(mStack.size() - 1).mediaId);
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
        String current = mStack.isEmpty() ? null : mStack.get(0).mediaId;
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
        mStack.add(new Place(tab.getMediaId(), tab.getDescription().getTitle(), null, null));
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
        mSearching = false;
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
        mAvatar.setVisibility(inside ? View.GONE : View.VISIBLE);
        mTabsScroll.setVisibility(inside || mSearching ? View.GONE : View.VISIBLE);
        mTitle.setTextSize(inside ? 19 : 23);
        mTitle.setText(inside ? mStack.get(mStack.size() - 1).title : greeting());
    }

    private static String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 6 && hour < 13) {
            return "Buenos días";
        }
        if (hour >= 13 && hour < 21) {
            return "Buenas tardes";
        }
        return "Buenas noches";
    }

    private static boolean isTab(String mediaId) {
        return mediaId != null && mediaId.startsWith("tab:");
    }

    /** Big top (cover, name, play button) for an artist, a kind of music, an album, a mix or a list. */
    private static final class Hero {
        final String title;
        final String subtitle;
        final Uri art;
        final boolean round;

        Hero(String title, String subtitle, Uri art, boolean round) {
            this.title = title;
            this.subtitle = subtitle;
            this.art = art;
            this.round = round;
        }
    }

    /**
     * Like Spotify: shelves of covers that scroll sideways on «Para ti»; on an artist, a style or
     * a list, a big top and the songs one under another (the other shelves still sideways).
     */
    private void showItems(List<MediaItem> children, String emptyText) {
        mProgress.setVisibility(View.GONE);
        String current = mStack.isEmpty() ? null : mStack.get(mStack.size() - 1).mediaId;
        boolean home = !mSearching && (current == null || isTab(current));

        // Consecutive items with the same group title make a shelf
        List<String> titles = new ArrayList<>();
        List<List<MediaItem>> shelves = new ArrayList<>();
        String lastGroup = null;
        for (MediaItem item : children) {
            Bundle extras = item.getDescription().getExtras();
            String group = extras != null ? extras.getString(GROUP_HINT) : null;
            if (shelves.isEmpty() || !TextUtils.equals(group, lastGroup)) {
                titles.add(group);
                shelves.add(new ArrayList<>());
            }
            shelves.get(shelves.size() - 1).add(item);
            lastGroup = group;
        }
        boolean manyShelves = shelves.size() > 1;

        List<Object> rows = new ArrayList<>();
        if (!mSearching && current != null && (!isTab(current) || isPreferenceTab(current)) && !children.isEmpty()) {
            rows.add(hero(children));
        }
        if (!mSearching && "tab:lists".equals(current) && !FamilyProfiles.instance(this).getCurrent().isKid) {
            rows.add(NEW_LIST);
        }
        for (int i = 0; i < shelves.size(); i++) {
            List<MediaItem> shelf = shelves.get(i);
            String title = titles.get(i);
            boolean songs = allPlayable(shelf);
            boolean sideways = manyShelves && shelf.size() > 1 && (home || !songs);
            if (title != null) {
                rows.add(title);
            }
            if (sideways) {
                rows.add(new Shelf(shelf));
            } else {
                rows.addAll(shelf);
            }
        }
        mAdapter.setItems(rows);
        mList.scrollToPosition(0);
        if (children.isEmpty()) {
            showMessage(emptyText);
        } else {
            mEmpty.setVisibility(View.GONE);
        }
    }

    /** The tabs of an artist, a kind of music or an album (chosen from what you listen to). */
    private static boolean isPreferenceTab(String mediaId) {
        return mediaId.startsWith("art:") || mediaId.startsWith("gen:") || mediaId.startsWith("alb:");
    }

    private Hero hero(List<MediaItem> children) {
        Place place = mStack.get(mStack.size() - 1);
        Uri art = place.icon;
        if (art == null || "android.resource".equals(art.getScheme())) {
            art = null;
            for (MediaItem item : children) {
                if (item.getDescription().getIconUri() != null) {
                    art = item.getDescription().getIconUri();
                    break;
                }
            }
        }
        String subtitle = place.subtitle;
        if (TextUtils.isEmpty(subtitle)) {
            String id = place.mediaId;
            if (id.startsWith("art:")) {
                subtitle = "Artista · según lo que escuchas";
            } else if (id.startsWith("gen:")) {
                subtitle = "Tu estilo de música";
            } else if (id.startsWith("alb:")) {
                subtitle = "Álbum";
            } else if (id.startsWith("mix:")) {
                subtitle = "Canciones parecidas";
            } else if (id.equals(HIDDEN_LISTS)) {
                subtitle = "No parecen de música · mantén pulsada una para mostrarla";
            } else {
                subtitle = countSongs(children);
            }
        }
        return new Hero(place.title, subtitle, art, place.mediaId.startsWith("art:") || place.mediaId.startsWith("ch:"));
    }

    private static String countSongs(List<MediaItem> children) {
        int count = 0;
        for (MediaItem item : children) {
            if (item.isPlayable()) {
                count++;
            }
        }
        return count == 1 ? "1 canción" : count + " canciones";
    }

    private static boolean allPlayable(List<MediaItem> items) {
        for (MediaItem item : items) {
            if (!item.isPlayable()) {
                return false;
            }
        }
        return true;
    }

    private void playFirst() {
        for (Object row : mAdapter.mItems) {
            if (row instanceof MediaItem && ((MediaItem) row).isPlayable()) {
                onItemClicked((MediaItem) row);
                return;
            }
            if (row instanceof Shelf) {
                for (MediaItem item : ((Shelf) row).items) {
                    if (item.isPlayable()) {
                        onItemClicked(item);
                        return;
                    }
                }
            }
        }
        Toast.makeText(this, "No hay canciones para reproducir aquí", Toast.LENGTH_SHORT).show();
    }

    private void showMessage(String text) {
        mProgress.setVisibility(View.GONE);
        mEmpty.setText(text);
        mEmpty.setVisibility(View.VISIBLE);
    }

    private void onItemClicked(MediaItem item) {
        MediaDescriptionCompat description = item.getDescription();
        if (item.isBrowsable()) {
            mStack.add(new Place(item.getMediaId(), description.getTitle(), description.getSubtitle(), description.getIconUri()));
            if (mSearching) {
                mSearchBar.setVisibility(View.GONE);
                mTop.setVisibility(View.VISIBLE);
                hideKeyboard();
            }
            load(item.getMediaId());
        } else if (item.isPlayable() && mController != null) {
            mSource = currentPlaceTitle();
            mController.getTransportControls().playFromMediaId(item.getMediaId(), null);
            showPlayer();
        }
    }

    private MusicListsMenu listsMenu() {
        return new MusicListsMenu(this, mController);
    }

    /**
     * Long press. A song: add it to lists (or take it out of the list open now). A YouTube list:
     * hide it from the music (or show it again), rename it, delete it.
     */
    private boolean onItemLongClicked(MediaItem item) {
        String id = item.getMediaId();
        if (id == null || mController == null || FamilyProfiles.instance(this).getCurrent().isKid) {
            return false;
        }
        CharSequence name = item.getDescription().getTitle() != null ? item.getDescription().getTitle() : "";
        if (id.startsWith("v:")) {
            int at = id.indexOf('@');
            String videoId = id.substring(2, at > 0 ? at : id.length());
            String openList = mSubscribed != null && mSubscribed.startsWith("pl:") ? mSubscribed.substring(3) : null;
            String[] options = openList != null
                    ? new String[]{"Añadir a una lista…", "Quitar de esta lista"}
                    : new String[]{"Añadir a una lista…"};
            new AlertDialog.Builder(this)
                    .setTitle(name)
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            listsMenu().addSong(videoId, name);
                        } else {
                            listsMenu().removeSong(openList, videoId, name, mStack.get(mStack.size() - 1).title);
                        }
                    })
                    .show();
            return true;
        }
        if (!id.startsWith("pl:")) {
            return false;
        }
        String playlistId = id.substring("pl:".length());
        boolean inHidden = HIDDEN_LISTS.equals(mSubscribed);
        String[] options = {inHidden ? "Mostrar en la música" : "Ocultar (no es música)", "Cambiar nombre", "Borrar lista"};
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        Bundle extras = new Bundle();
                        extras.putString(CarMusicService.EXTRA_PLAYLIST, playlistId);
                        mController.getTransportControls().sendCustomAction(
                                inHidden ? CarMusicService.ACTION_SHOW_LIST : CarMusicService.ACTION_HIDE_LIST, extras);
                        Toast.makeText(this, inHidden ? "Vuelve a salir en «Tus listas»"
                                : "Lista ocultada. La tienes en «Listas ocultas»", Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        listsMenu().rename(playlistId, name.toString());
                    } else {
                        listsMenu().delete(playlistId, name.toString(), () -> {
                        });
                    }
                })
                .show();
        return true;
    }

    /** «+» in the player: the song playing goes to lists. */
    private void addPlayingToList() {
        MediaMetadataCompat metadata = mController != null ? mController.getMetadata() : null;
        String videoId = metadata != null ? metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID) : null;
        if (videoId == null) {
            return;
        }
        if (FamilyProfiles.instance(this).getCurrent().isKid) {
            Toast.makeText(this, "Los perfiles infantiles no tienen listas", Toast.LENGTH_SHORT).show();
            return;
        }
        listsMenu().addSong(videoId, metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
    }

    private String currentPlaceTitle() {
        if (mSearching) {
            return "Búsqueda";
        }
        return mStack.isEmpty() ? getString(R.string.app_name) : mStack.get(mStack.size() - 1).title;
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
        mSearching = false;
        if (!mStack.isEmpty()) {
            load(mStack.get(mStack.size() - 1).mediaId);
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
        mSearching = true;
        updateHeader();
        mAdapter.setItems(new ArrayList<>());
        mEmpty.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
        mBrowser.search(query.trim(), null, new MediaBrowserCompat.SearchCallback() {
            @Override
            public void onSearchResult(@NonNull String q, Bundle extras, @NonNull List<MediaItem> items) {
                if (mSearching) {
                    showItems(items, "No se ha encontrado nada.");
                }
            }

            @Override
            public void onError(@NonNull String q, Bundle extras) {
                if (mSearching) {
                    showItems(new ArrayList<>(), "No se ha podido buscar. Comprueba la conexión.");
                }
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
        mMini.setOnClickListener(v -> showPlayer());
        mMiniPlay.setOnClickListener(v -> togglePlay());
        findViewById(R.id.music_mini_next).setOnClickListener(v -> skipToNext());
        findViewById(R.id.music_player_close).setOnClickListener(v -> hidePlayer());
        findViewById(R.id.music_player_add).setOnClickListener(v -> addPlayingToList());
        findViewById(R.id.music_player_queue).setOnClickListener(v -> {
            showQueue();
            mQueue.setVisibility(View.VISIBLE);
        });
        findViewById(R.id.music_queue_close).setOnClickListener(v -> mQueue.setVisibility(View.GONE));
        mPlayerPlay.setOnClickListener(v -> togglePlay());
        findViewById(R.id.music_player_next).setOnClickListener(v -> skipToNext());
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
        // The cover: a square as big as the space between the top and the title allows
        mPlayerArtBox.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int size = Math.min(right - left, bottom - top);
            ViewGroup.LayoutParams params = mPlayerArt.getLayoutParams();
            if (size > 0 && (params.width != size || params.height != size)) {
                params.width = size;
                params.height = size;
                mPlayerArt.post(() -> mPlayerArt.setLayoutParams(params));
            }
        });
    }

    private void skipToNext() {
        if (mController != null) {
            mController.getTransportControls().skipToNext();
        }
    }

    private void showPlayer() {
        if (mPlayer.getVisibility() == View.VISIBLE) {
            return;
        }
        mPlayerSource.setText(mSource);
        mPlayer.setVisibility(View.VISIBLE);
        mPlayer.setTranslationY(getResources().getDisplayMetrics().heightPixels * 0.25f);
        mPlayer.setAlpha(0f);
        mPlayer.animate().translationY(0).alpha(1f).setDuration(220).start();
        mPlayerTitle.setSelected(true); // long titles scroll
        updateProgress();
    }

    private void hidePlayer() {
        mQueue.setVisibility(View.GONE);
        mPlayer.animate().translationY(getResources().getDisplayMetrics().heightPixels * 0.25f).alpha(0f).setDuration(180)
                .withEndAction(() -> {
                    mPlayer.setVisibility(View.GONE);
                    mPlayer.setTranslationY(0);
                    mPlayer.setAlpha(1f);
                }).start();
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
            mQueueAdapter.notifyDataSetChanged();
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            showPlayback();
        }

        @Override
        public void onQueueChanged(List<MediaSessionCompat.QueueItem> queue) {
            showQueue();
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
        String playing = metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
        if (!TextUtils.equals(playing, mPlayingId)) {
            mPlayingId = playing;
            mAdapter.notifyDataSetChanged(); // mark the song playing
        }
        String title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
        String album = metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM);
        mMiniTitle.setText(title);
        mMiniArtist.setText(artist);
        if (!TextUtils.equals(mPlayerTitle.getText(), title)) {
            mPlayerTitle.setText(title);
        }
        mPlayerArtist.setText(artist);
        mPlayerAlbum.setText(album);
        mPlayerAlbum.setVisibility(TextUtils.isEmpty(album) ? View.GONE : View.VISIBLE);
        if (TextUtils.isEmpty(mSource)) {
            mSource = getString(R.string.app_name);
            mPlayerSource.setText(mSource);
        }
        String art = metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI);
        if (art != null) {
            Uri uri = Uri.parse(art);
            Object source = MusicArtLoader.source(this, uri); // YouTube now, the iTunes cover when found
            if (!art.equals(mArtUri) || !source.equals(mArtSource)) {
                mArtUri = art;
                mArtSource = source;
                Glide.with(mMiniArt).load(source).centerCrop().into(mMiniArt);
                Glide.with(mPlayerArt).load(source).centerCrop().into(mPlayerArt);
                loadColor(source);
                MusicArtLoader.lookUp(this, uri);
            }
        }
        boolean isPlaying = state != null && (state.getState() == PlaybackStateCompat.STATE_PLAYING
                || state.getState() == PlaybackStateCompat.STATE_BUFFERING);
        int icon = isPlaying ? R.drawable.mobile_ic_pause : R.drawable.mobile_ic_play;
        mMiniPlay.setImageResource(icon);
        mPlayerPlay.setImageResource(icon);
        updateProgress();
    }

    // ---------------------------------------------------------------- colours after the cover

    /** The main colour of the cover tints the player, the mini player and the top of the screen. */
    private void loadColor(Object art) {
        Glide.with(this).asBitmap().load(art).into(new CustomTarget<Bitmap>(48, 48) {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                if (!isFinishing()) {
                    applyColor(mainColor(resource));
                }
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
                // nothing to free
            }
        });
    }

    /** The most colourful average of the picture (greys count less), not too light or dark. */
    private static int mainColor(Bitmap bitmap) {
        long r = 0;
        long g = 0;
        long b = 0;
        long weight = 0;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float[] hsv = new float[3];
        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int pixel = bitmap.getPixel(x, y);
                Color.colorToHSV(pixel, hsv);
                int w = 1 + (int) (hsv[1] * hsv[2] * 10);
                r += Color.red(pixel) * w;
                g += Color.green(pixel) * w;
                b += Color.blue(pixel) * w;
                weight += w;
            }
        }
        if (weight == 0) {
            return 0xFF2A3B36;
        }
        int color = Color.rgb((int) (r / weight), (int) (g / weight), (int) (b / weight));
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.25f);
        hsv[2] = Math.max(0.35f, Math.min(0.75f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    private static int darken(int color, float amount) {
        return Color.rgb((int) (Color.red(color) * (1 - amount)), (int) (Color.green(color) * (1 - amount)),
                (int) (Color.blue(color) * (1 - amount)));
    }

    private void applyColor(int color) {
        GradientDrawable player = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{darken(color, 0.25f), darken(color, 0.6f), BG});
        mPlayer.setBackground(player);
        GradientDrawable mini = new GradientDrawable();
        mini.setCornerRadius(10 * getResources().getDisplayMetrics().density);
        mini.setColor(darken(color, 0.45f));
        mMini.setBackground(mini);
        mMini.setClipToOutline(true);
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(darken(color, 0.4f) & 0x00FFFFFF) | 0x99000000, BG & 0x00FFFFFF});
        mGlow.setBackground(glow);
    }

    // ---------------------------------------------------------------- progress

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
        long duration = duration();
        long position = position();
        float fraction = duration > 0 ? Math.min(1f, position / (float) duration) : 0f;
        mMiniProgress.setPivotX(0);
        mMiniProgress.setScaleX(fraction);
        if (mSeeking) {
            return;
        }
        mPlayerTime.setText(formatTime(position));
        mPlayerDuration.setText(duration > 0 ? formatTime(duration) : "");
        mPlayerSeek.setProgress((int) (fraction * 1000));
    }

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            if (mMini.getVisibility() == View.VISIBLE || mPlayer.getVisibility() == View.VISIBLE) {
                updateProgress();
            }
            mHandler.postDelayed(this, 500);
        }
    };

    private static String formatTime(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    // ---------------------------------------------------------------- queue

    private void showQueue() {
        List<MediaSessionCompat.QueueItem> queue = mController != null ? mController.getQueue() : null;
        mQueueAdapter.setItems(queue != null ? queue : new ArrayList<>());
    }

    private long activeQueueId() {
        PlaybackStateCompat state = mController != null ? mController.getPlaybackState() : null;
        return state != null ? state.getActiveQueueItemId() : MediaSessionCompat.QueueItem.UNKNOWN_ID;
    }

    private final class QueueAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<MediaSessionCompat.QueueItem> mItems = new ArrayList<>();

        void setItems(List<MediaSessionCompat.QueueItem> items) {
            mItems.clear();
            mItems.addAll(items);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.music_row, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            MediaSessionCompat.QueueItem item = mItems.get(position);
            boolean current = item.getQueueId() == activeQueueId();
            bindRow(holder.itemView, item.getDescription(), false, current);
            holder.itemView.setAlpha(item.getQueueId() < activeQueueId() ? 0.5f : 1f); // already played
            holder.itemView.setOnClickListener(v -> {
                if (mController != null) {
                    mController.getTransportControls().skipToQueueItem(item.getQueueId());
                }
            });
        }
    }

    // ---------------------------------------------------------------- lists

    private boolean isPlaying(MediaDescriptionCompat description) {
        String id = description.getMediaId();
        return mPlayingId != null && id != null && id.startsWith("v:" + mPlayingId + "@");
    }

    private static boolean isRound(MediaDescriptionCompat description) {
        String id = description.getMediaId();
        return id != null && (id.startsWith("art:") || id.startsWith("ch:"));
    }

    private void bindArt(ImageView view, MediaDescriptionCompat description) {
        view.setBackgroundResource(isRound(description) ? R.drawable.music_round_bg
                : (view.getId() == R.id.music_hero_art ? R.drawable.music_art_big_bg : R.drawable.music_art_bg));
        view.setClipToOutline(true);
        Uri icon = description.getIconUri();
        if (icon != null) {
            MusicArtLoader.into(view, icon);
        } else {
            Glide.with(view).clear(view);
            view.setImageDrawable(null);
        }
    }

    private void bindRow(View view, MediaDescriptionCompat description, boolean browsable, boolean playing) {
        TextView title = view.findViewById(R.id.music_row_title);
        title.setText(description.getTitle());
        title.setTextColor(playing ? getResources().getColor(R.color.music_accent) : getResources().getColor(R.color.family_text));
        TextView subtitle = view.findViewById(R.id.music_row_subtitle);
        CharSequence sub = description.getSubtitle();
        subtitle.setText(sub);
        subtitle.setVisibility(TextUtils.isEmpty(sub) ? View.GONE : View.VISIBLE);
        view.findViewById(R.id.music_row_more).setVisibility(browsable ? View.VISIBLE : View.GONE);
        bindArt(view.findViewById(R.id.music_row_art), description);
    }

    /** «Nueva lista» at the top of «Tus listas». */
    private static final Object NEW_LIST = new Object();

    /** A shelf of covers that scrolls sideways. */
    private static final class Shelf {
        final List<MediaItem> items;

        Shelf(List<MediaItem> items) {
            this.items = items;
        }
    }

    private final class MusicAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_ITEM = 1;
        private static final int TYPE_SHELF = 2;
        private static final int TYPE_HERO = 3;
        private final List<Object> mItems = new ArrayList<>();
        private final RecyclerView.RecycledViewPool mCardPool = new RecyclerView.RecycledViewPool();

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
            Object item = mItems.get(position);
            if (item instanceof String) {
                return TYPE_HEADER;
            }
            if (item instanceof Shelf) {
                return TYPE_SHELF;
            }
            if (item instanceof Hero) {
                return TYPE_HERO;
            }
            return TYPE_ITEM;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            int layout;
            switch (viewType) {
                case TYPE_HEADER:
                    layout = R.layout.music_section;
                    break;
                case TYPE_SHELF:
                    layout = R.layout.music_shelf;
                    break;
                case TYPE_HERO:
                    layout = R.layout.music_hero;
                    break;
                default:
                    layout = R.layout.music_row;
            }
            View view = inflater.inflate(layout, parent, false);
            if (viewType == TYPE_SHELF) {
                RecyclerView shelf = (RecyclerView) view;
                shelf.setLayoutManager(new LinearLayoutManager(parent.getContext(), LinearLayoutManager.HORIZONTAL, false));
                shelf.setRecycledViewPool(mCardPool);
                shelf.setAdapter(new CardAdapter());
            }
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = mItems.get(position);
            View view = holder.itemView;
            if (item instanceof String) {
                ((TextView) view).setText((String) item);
            } else if (item instanceof Shelf) {
                CardAdapter cards = (CardAdapter) ((RecyclerView) view).getAdapter();
                if (view.getTag() != item) {
                    view.setTag(item);
                    cards.setItems(((Shelf) item).items);
                    ((RecyclerView) view).scrollToPosition(0);
                } else {
                    cards.notifyDataSetChanged(); // same shelf: only the song playing changes
                }
            } else if (item instanceof Hero) {
                Hero hero = (Hero) item;
                ImageView art = view.findViewById(R.id.music_hero_art);
                art.setBackgroundResource(hero.round ? R.drawable.music_round_bg : R.drawable.music_art_big_bg);
                art.setClipToOutline(true);
                if (hero.art != null) {
                    MusicArtLoader.into(art, hero.art);
                } else {
                    Glide.with(art).clear(art);
                    art.setImageResource(R.mipmap.app_icon);
                }
                ((TextView) view.findViewById(R.id.music_hero_title)).setText(hero.title);
                ((TextView) view.findViewById(R.id.music_hero_subtitle)).setText(hero.subtitle);
                view.findViewById(R.id.music_hero_play).setOnClickListener(v -> playFirst());
            } else {
                if (item == NEW_LIST) {
                    TextView title = view.findViewById(R.id.music_row_title);
                    title.setText("Nueva lista");
                    title.setTextColor(getResources().getColor(R.color.family_text));
                    TextView subtitle = view.findViewById(R.id.music_row_subtitle);
                    subtitle.setText("Crea una lista de canciones en tu cuenta");
                    subtitle.setVisibility(View.VISIBLE);
                    view.findViewById(R.id.music_row_more).setVisibility(View.GONE);
                    ImageView art = view.findViewById(R.id.music_row_art);
                    Glide.with(art).clear(art);
                    art.setBackgroundResource(R.drawable.music_art_bg);
                    art.setScaleType(ImageView.ScaleType.CENTER);
                    art.setImageResource(R.drawable.music_ic_add);
                    view.setOnClickListener(v -> listsMenu().newList(null));
                    view.setOnLongClickListener(null);
                    return;
                }
                MediaItem media = (MediaItem) item;
                ((ImageView) view.findViewById(R.id.music_row_art)).setScaleType(ImageView.ScaleType.CENTER_CROP);
                bindRow(view, media.getDescription(), media.isBrowsable(), isPlaying(media.getDescription()));
                view.setOnClickListener(v -> onItemClicked(media));
                view.setOnLongClickListener(v -> onItemLongClicked(media));
            }
        }
    }

    private final class CardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<MediaItem> mCards = new ArrayList<>();

        void setItems(List<MediaItem> items) {
            mCards.clear();
            mCards.addAll(items);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mCards.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.music_card, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            MediaItem media = mCards.get(position);
            MediaDescriptionCompat description = media.getDescription();
            View view = holder.itemView;
            TextView title = view.findViewById(R.id.music_card_title);
            title.setText(description.getTitle());
            title.setGravity(isRound(description) ? android.view.Gravity.CENTER_HORIZONTAL : android.view.Gravity.START);
            TextView subtitle = view.findViewById(R.id.music_card_subtitle);
            subtitle.setText(description.getSubtitle());
            subtitle.setGravity(isRound(description) ? android.view.Gravity.CENTER_HORIZONTAL : android.view.Gravity.START);
            subtitle.setVisibility(TextUtils.isEmpty(description.getSubtitle()) ? View.GONE : View.VISIBLE);
            bindArt(view.findViewById(R.id.music_card_art), description);
            view.findViewById(R.id.music_card_playing).setVisibility(isPlaying(description) ? View.VISIBLE : View.GONE);
            view.setOnClickListener(v -> onItemClicked(media));
            view.setOnLongClickListener(v -> onItemLongClicked(media));
        }
    }
}
