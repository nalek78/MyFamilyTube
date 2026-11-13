package es.myfamilytube.auto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.MediaBrowserServiceCompat;

import com.google.android.exoplayer2.Player;
import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.misc.ContentGate;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import es.myfamilytube.profiles.FamilyAccounts;
import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

/**
 * My Family Music in Android Auto and on the phone, like a music app: «Para ti» and tabs chosen
 * from what the profile listens to (an artist, a kind of music, an album), voice search, the
 * steering wheel buttons and, when a list ends, similar songs. Only the sound is played, with the parental
 * control of the profile chosen on the phone.
 */
public class CarMusicService extends MediaBrowserServiceCompat implements CarPlayer.Listener {
    private static final String ROOT = "root";
    private static final String TAB_FOR_YOU = "tab:foryou";
    private static final String TAB_HOME = "tab:home";
    private static final String TAB_LISTS = "tab:lists";
    private static final String TAB_RECENT = "tab:recent";
    private static final String TAB_CHANNELS = "tab:channels";
    private static final String PLAYLIST = "pl:";
    private static final String CHANNEL = "ch:";
    private static final String VIDEO = "v:";
    private static final String SEARCH = "search:";
    private static final String ARTIST = "art:";
    private static final String GENRE = "gen:";
    private static final String ALBUM = "alb:";
    private static final String MIX = "mix:";
    private static final String HIDDEN_LISTS = "hidden:lists";
    /** From the phone (long press on a list): it isn't music / it is music. */
    public static final String ACTION_HIDE_LIST = "es.myfamilymusic.HIDE_LIST";
    public static final String ACTION_SHOW_LIST = "es.myfamilymusic.SHOW_LIST";
    public static final String EXTRA_PLAYLIST = "playlist";
    /** From the phone: lists were created or changed on YouTube (load them again). */
    public static final String ACTION_LISTS_CHANGED = "es.myfamilymusic.LISTS_CHANGED";
    /** From the phone: a list was created here (it is music); extra: its name. */
    public static final String ACTION_LIST_CREATED = "es.myfamilymusic.LIST_CREATED";
    public static final String EXTRA_NAME = "name";
    /** Car button: the song playing goes to «Mis favoritas» (the list is created the first time). */
    private static final String ACTION_SAVE_SONG = "es.myfamilymusic.SAVE_SONG";
    static final String FAVOURITES = "Mis favoritas";

    private static final String STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED";
    private static final String STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    private static final String STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    private static final String STYLE_GROUP = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT";
    private static final String SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED";
    private static final int STYLE_LIST = 1;
    private static final int STYLE_GRID = 2;

    private static final int MAX_ITEMS = 80;
    private static final int MAX_PER_SHELF = 12;
    private static final int MAX_SCREEN = 160;
    private static final int MAX_TABS = 4;
    private static final long TABS_MS = 3 * 60 * 60 * 1000;
    private static final long MAX_SONG_MS = 15 * 60 * 1000;
    private static final long CACHE_MS = 10 * 60 * 1000;
    private static final String CHANNEL_ID = "car_playback";
    private static final int NOTIFICATION_ID = 4810;

    private static CarMusicService sInstance;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private MediaSessionCompat mSession;
    private CarPlayer mPlayer;
    private final List<Disposable> mActions = new ArrayList<>();

    /** Browse results already loaded (Android Auto asks again and again). */
    private final Map<String, List<MediaItem>> mChildren = new HashMap<>();
    private final Map<String, Long> mChildrenTime = new HashMap<>();
    /** The videos of each list: the queue when you play one of them. */
    private final Map<String, List<Video>> mLists = new HashMap<>();
    /** Playlists and channels seen, to open them. */
    private final Map<String, Video> mFolders = new HashMap<>();

    private boolean mForeground;
    private int mSkipsInARow;
    private long mLastTickMs;
    private String mError;
    /** The tabs chosen from what the profile listens to (they don't change while driving). */
    private List<MediaItem> mTabs;
    private long mTabsTime;

    /** The phone player opened a video: the car stops (both share the engine). */
    public static void onPhonePlayerOpened() {
        CarMusicService service = sInstance;
        if (service != null && service.mPlayer != null) {
            service.stopPlayback(false);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mPlayer = new CarPlayer(this, this);
        mCache = new CarSongCache(this);
        mPlayer.setSongDir(mCache.dir());
        mSession = new MediaSessionCompat(this, "MyFamilyTubeCar");
        mSession.setCallback(new SessionCallback());
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            mSession.setSessionActivity(PendingIntent.getActivity(this, 0, open, flags));
        }
        setSessionToken(mSession.getSessionToken());
        updateState();
        FamilyProfiles.instance(this).addListener(() -> mHandler.post(this::onProfileChanged));
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        mHandler.removeCallbacksAndMessages(null);
        disposeActions();
        stopPlayback(true);
        mCache.shutdown();
        mWorker.shutdownNow();
        mSession.release();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (!mPlayer.uiPlayWhenReady()) {
            stopSelf();
        }
    }

    private FamilyProfile profile() {
        return FamilyProfiles.instance(this).getCurrent();
    }

    private void onProfileChanged() {
        // Other profile, other rules and account: everything is loaded again
        mChildren.clear();
        mChildrenTime.clear();
        mLists.clear();
        mTabs = null;
        mSavedIds.clear();
        notifyChildrenChanged(ROOT);
    }

    // ---------------------------------------------------------------- browsing

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        Bundle extras = new Bundle();
        extras.putBoolean(STYLE_SUPPORTED, true);
        extras.putBoolean(SEARCH_SUPPORTED, true);
        extras.putInt(STYLE_BROWSABLE, STYLE_GRID);
        extras.putInt(STYLE_PLAYABLE, STYLE_LIST);
        return new BrowserRoot(ROOT, extras);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaItem>> result) {
        if (ROOT.equals(parentId)) {
            result.sendResult(tabs());
            return;
        }
        List<MediaItem> cached = mChildren.get(parentId);
        Long time = mChildrenTime.get(parentId);
        if (cached != null && time != null && System.currentTimeMillis() - time < CACHE_MS) {
            result.sendResult(cached);
            return;
        }
        result.detach();
        load(parentId, items -> {
            mChildren.put(parentId, items);
            mChildrenTime.put(parentId, System.currentTimeMillis());
            result.sendResult(items);
        });
    }

    @Override
    public void onSearch(@NonNull String query, Bundle extras, @NonNull Result<List<MediaItem>> result) {
        result.detach();
        load(SEARCH + query, result::sendResult);
    }

    private MusicTaste taste() {
        return MusicTaste.of(this, profile().id);
    }

    /**
     * The tabs (Android Auto shows up to 4): «Para ti» and then what this profile listens to the
     * most: an artist, a kind of music, an album… They change as the profile listens to music.
     */
    private List<MediaItem> tabs() {
        List<MediaItem> tabs = new ArrayList<>();
        if (profile().isKid) {
            tabs.add(tab(TAB_HOME, "Inicio", R.drawable.mobile_ic_home, STYLE_GRID, STYLE_GRID));
            if (!profile().approvedChannels.isEmpty()) {
                tabs.add(tab(TAB_CHANNELS, "Tus canales", R.drawable.mobile_ic_subs, STYLE_GRID, STYLE_LIST));
            }
            return tabs;
        }
        if (mTabs != null && System.currentTimeMillis() - mTabsTime < TABS_MS) {
            return mTabs;
        }
        tabs.add(tab(TAB_FOR_YOU, "Para ti", R.drawable.mobile_ic_home, STYLE_GRID, STYLE_LIST));
        MusicTaste taste = taste();
        List<MusicTaste.Entry> artists = taste.topArtists(3);
        List<MusicTaste.Entry> genres = taste.topGenres(2);
        List<MusicTaste.Entry> albums = taste.topAlbums(1);
        List<MediaItem> wanted = new ArrayList<>();
        if (artists.size() > 0) wanted.add(artistTab(artists.get(0)));
        if (genres.size() > 0) wanted.add(genreTab(genres.get(0)));
        if (albums.size() > 0) wanted.add(albumTab(albums.get(0)));
        if (artists.size() > 1) wanted.add(artistTab(artists.get(1)));
        if (genres.size() > 1) wanted.add(genreTab(genres.get(1)));
        if (artists.size() > 2) wanted.add(artistTab(artists.get(2)));
        for (MediaItem item : wanted) {
            if (tabs.size() < MAX_TABS) {
                tabs.add(item);
            }
        }
        if (tabs.size() < MAX_TABS) {
            tabs.add(tab(TAB_LISTS, "Tus listas", R.drawable.mobile_ic_library, STYLE_GRID, STYLE_LIST));
        }
        if (tabs.size() < MAX_TABS) {
            tabs.add(tab(TAB_CHANNELS, "Canales", R.drawable.mobile_ic_subs, STYLE_GRID, STYLE_LIST));
        }
        mTabs = tabs;
        mTabsTime = System.currentTimeMillis();
        return tabs;
    }

    private MediaItem artistTab(MusicTaste.Entry artist) {
        return tab(ARTIST + Uri.encode(artist.name), artist.name, R.drawable.mobile_ic_mic, STYLE_GRID, STYLE_LIST);
    }

    private MediaItem genreTab(MusicTaste.Entry genre) {
        return tab(GENRE + Uri.encode(genre.name), genre.name, R.drawable.mobile_ic_audio, STYLE_GRID, STYLE_LIST);
    }

    private MediaItem albumTab(MusicTaste.Entry album) {
        return tab(albumId(album.artist, album.name), album.name, R.drawable.mobile_ic_library, STYLE_GRID, STYLE_LIST);
    }

    private static String albumId(String artist, String album) {
        return ALBUM + Uri.encode(artist != null ? artist : "") + "|" + Uri.encode(album);
    }

    private MediaItem tab(String id, String title, int icon, int browsableStyle, int playableStyle) {
        Bundle extras = new Bundle();
        extras.putInt(STYLE_BROWSABLE, browsableStyle);
        extras.putInt(STYLE_PLAYABLE, playableStyle);
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setIconUri(Uri.parse("android.resource://" + getPackageName() + "/" + icon))
                .setExtras(extras)
                .build();
        return new MediaItem(description, MediaItem.FLAG_BROWSABLE);
    }

    private interface Done {
        void onItems(List<MediaItem> items);
    }

    private ContentService content() {
        return YouTubeServiceManager.instance().getContentService();
    }

    private void load(String parentId, Done done) {
        ContentService content = content();
        Observable<List<MediaGroup>> rows = null;
        Observable<MediaGroup> grid = null;
        boolean kid = profile().isKid;

        if (TAB_FOR_YOU.equals(parentId) && !kid) {
            loadParts(parentId, forYou(), done);
            return;
        } else if (parentId.startsWith(ARTIST) && !kid) {
            loadParts(parentId, artistParts(Uri.decode(parentId.substring(ARTIST.length()))), done);
            return;
        } else if (parentId.startsWith(GENRE) && !kid) {
            loadParts(parentId, genreParts(Uri.decode(parentId.substring(GENRE.length()))), done);
            return;
        } else if (parentId.startsWith(ALBUM) && !kid) {
            String rest = parentId.substring(ALBUM.length());
            int bar = rest.indexOf('|');
            if (bar >= 0) {
                loadParts(parentId, albumParts(Uri.decode(rest.substring(0, bar)), Uri.decode(rest.substring(bar + 1))), done);
                return;
            }
        } else if (parentId.startsWith(MIX)) {
            grid = mixObserve(parentId.substring(MIX.length()));
        } else if (TAB_HOME.equals(parentId)) {
            rows = kid ? content.getKidsHomeObserve() : content.getMusicObserve();
        } else if (TAB_LISTS.equals(parentId) && !kid) {
            musicPlaylists(false, null, MAX_ITEMS, done); // only the lists that are music
            return;
        } else if (HIDDEN_LISTS.equals(parentId) && !kid) {
            musicPlaylists(true, null, MAX_ITEMS, done);
            return;
        } else if (TAB_RECENT.equals(parentId) && !kid) {
            grid = content.getHistoryObserve();
        } else if (TAB_CHANNELS.equals(parentId)) {
            if (kid) {
                done.onItems(kidChannels());
                return;
            }
            grid = content.getSubscribedChannelsObserve();
        } else if (parentId.startsWith(PLAYLIST)) {
            Video folder = mFolders.get(parentId);
            if (folder != null) {
                grid = playlistObserve(folder);
            }
        } else if (parentId.startsWith(CHANNEL)) {
            rows = content.getChannelObserve(parentId.substring(CHANNEL.length()));
        } else if (parentId.startsWith(SEARCH)) {
            Done searchOnce = once(done);
            mActions.add(content.getSearchObserve(parentId.substring(SEARCH.length())).subscribe(
                    groups -> mHandler.post(() -> searchOnce.onItems(safeSearch(parentId, groups))),
                    error -> mHandler.post(() -> searchOnce.onItems(new ArrayList<>())),
                    () -> mHandler.post(() -> searchOnce.onItems(new ArrayList<>()))));
            mHandler.postDelayed(() -> searchOnce.onItems(new ArrayList<>()), 20_000);
            return;
        }

        // Android Auto waits until it gets an answer, and only one: answer exactly once,
        // also when YouTube sends nothing, fails, or takes too long
        Done once = once(done);
        if (rows != null) {
            mActions.add(rows.subscribe(
                    groups -> mHandler.post(() -> once.onItems(safeRows(parentId, groups))),
                    error -> mHandler.post(() -> once.onItems(new ArrayList<>())),
                    () -> mHandler.post(() -> once.onItems(new ArrayList<>()))));
        } else if (grid != null) {
            mActions.add(grid.subscribe(
                    group -> mHandler.post(() -> {
                        List<MediaGroup> groups = new ArrayList<>();
                        groups.add(group);
                        once.onItems(safeRows(parentId, groups));
                    }),
                    error -> mHandler.post(() -> once.onItems(new ArrayList<>())),
                    () -> mHandler.post(() -> once.onItems(new ArrayList<>()))));
        } else {
            once.onItems(new ArrayList<>());
            return;
        }
        mHandler.postDelayed(() -> once.onItems(new ArrayList<>()), 20_000);
    }

    // ---------------------------------------------------------------- music lists, recommendations and news

    /** A list of songs found by the app (recommended, new…): one shelf whose songs are its queue. */
    private List<MediaItem> videoItems(String listKey, List<Video> videos, String groupTitle) {
        List<MediaItem> items = new ArrayList<>();
        mLists.put(listKey, videos);
        for (Video video : videos) {
            items.add(playable(listKey, video, groupTitle));
        }
        return items;
    }

    private interface VideosDone {
        void onVideos(List<List<Video>> lists);
    }

    /** Several YouTube requests at once; the videos of each (empty if it failed) when all arrive or time runs out. */
    private void collectVideos(List<Observable<List<MediaGroup>>> sources, long timeoutMs, VideosDone done) {
        List<List<Video>> results = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            results.add(null);
        }
        int[] waiting = {sources.size()};
        boolean[] sent = {false};
        Runnable finish = () -> {
            if (sent[0]) {
                return;
            }
            sent[0] = true;
            List<List<Video>> lists = new ArrayList<>();
            for (List<Video> list : results) {
                lists.add(list != null ? list : new ArrayList<>());
            }
            try {
                done.onVideos(lists);
            } catch (RuntimeException e) {
                es.myfamilytube.FamilyDiagnostics.instance(this).log("Música: error al preparar una lista: " + e);
            }
        };
        if (sources.isEmpty()) {
            finish.run();
            return;
        }
        for (int i = 0; i < sources.size(); i++) {
            int index = i;
            Runnable failed = () -> {
                if (results.get(index) == null) {
                    results.set(index, new ArrayList<>());
                    if (--waiting[0] == 0) {
                        finish.run();
                    }
                }
            };
            mActions.add(sources.get(i).subscribe(
                    groups -> mHandler.post(() -> {
                        if (results.get(index) == null) {
                            results.set(index, videosOf(groups));
                            if (--waiting[0] == 0) {
                                finish.run();
                            }
                        }
                    }),
                    error -> mHandler.post(failed),
                    () -> mHandler.post(failed)));
        }
        mHandler.postDelayed(finish, timeoutMs);
    }

    private static List<Video> videosOf(List<MediaGroup> groups) {
        List<Video> videos = new ArrayList<>();
        if (groups == null) {
            return videos;
        }
        for (MediaGroup group : groups) {
            if (group == null || group.getMediaItems() == null) {
                continue;
            }
            for (com.liskovsoft.mediaserviceinterfaces.data.MediaItem item : group.getMediaItems()) {
                Video video = item != null ? Video.from(item) : null;
                if (video != null) {
                    videos.add(video);
                }
            }
        }
        return videos;
    }

    /** A song that can go in a list: not live, not a Short, not too long, allowed for the profile. */
    private static boolean playableSong(Video video) {
        return video != null && video.videoId != null && !video.isLive && !video.isShorts
                && video.getDurationMs() <= MAX_SONG_MS && ContentGate.allows(video);
    }

    /**
     * The profile's YouTube lists that are music (hiddenOnly: the others). Lists not known yet are
     * looked at first (their first videos), at most 10 s.
     */
    private void musicPlaylists(boolean hiddenOnly, String groupTitle, int max, Done done) {
        Done once = once(done);
        String profileId = profile().id;
        mActions.add(content().getPlaylistsObserve().subscribe(
                group -> mHandler.post(() -> {
                    List<Video> lists = new ArrayList<>();
                    List<Video> unknown = new ArrayList<>();
                    List<Observable<List<MediaGroup>>> sources = new ArrayList<>();
                    List<MediaGroup> groups = new ArrayList<>();
                    groups.add(group);
                    for (Video list : videosOf(groups)) {
                        if (list.playlistId == null) {
                            continue;
                        }
                        lists.add(list);
                        if (MusicLists.isMusic(this, profileId, list) == null) {
                            unknown.add(list);
                            sources.add(asRows(playlistObserve(list)));
                        }
                    }
                    collectVideos(sources, 10_000, contents -> {
                        for (int i = 0; i < unknown.size(); i++) {
                            if (!contents.get(i).isEmpty()) {
                                MusicLists.saveAutomatic(this, unknown.get(i).playlistId, MusicLists.looksLikeMusic(this, contents.get(i)));
                            }
                        }
                        List<MediaItem> items = new ArrayList<>();
                        int hidden = 0;
                        for (Video list : lists) {
                            Boolean music = MusicLists.isMusic(this, profileId, list);
                            boolean isHidden = Boolean.FALSE.equals(music); // not known yet: shown
                            if (isHidden) {
                                hidden++;
                            }
                            if (isHidden == hiddenOnly && items.size() < max) {
                                items.add(folder(PLAYLIST + list.playlistId, list, groupTitle));
                            }
                        }
                        if (!hiddenOnly && groupTitle == null && hidden > 0) {
                            items.add(browsable(HIDDEN_LISTS, "Listas ocultas",
                                    hidden == 1 ? "1 lista que no es de música" : hidden + " listas que no son de música",
                                    Uri.parse("android.resource://" + getPackageName() + "/" + R.drawable.mobile_ic_library), null, STYLE_LIST));
                        }
                        once.onItems(items);
                    });
                }),
                error -> mHandler.post(() -> once.onItems(new ArrayList<>())),
                () -> mHandler.post(() -> once.onItems(new ArrayList<>()))));
        mHandler.postDelayed(() -> once.onItems(new ArrayList<>()), 25_000);
    }

    /** Songs you haven't heard yet, from the mixes of what you listened to lately (different artists). */
    private void recommended(String listKey, Done done) {
        MusicTaste taste = taste();
        List<MusicTaste.Song> seeds = new ArrayList<>();
        List<String> artists = new ArrayList<>();
        for (MusicTaste.Song song : taste.recent(40)) {
            String artist = song.artist != null ? MusicArt.normalize(song.artist) : song.videoId;
            if (!artists.contains(artist)) {
                artists.add(artist);
                seeds.add(song);
            }
            if (seeds.size() >= 3) {
                break;
            }
        }
        List<Observable<List<MediaGroup>>> sources = new ArrayList<>();
        for (MusicTaste.Song seed : seeds) {
            sources.add(asRows(mixObserve(seed.videoId)));
        }
        collectVideos(sources, 12_000, lists -> {
            List<Video> picked = roundRobin(lists, 20, 20, video -> playableSong(video) && !taste.knows(video.videoId));
            done.onItems(videoItems(listKey, picked, "Recomendadas para ti"));
        });
    }

    /** What your artists have published lately (only their songs, not other people's videos about them). */
    private void news(String listKey, List<String> artists, int perArtist, int max, String groupTitle, Done done) {
        List<Observable<List<MediaGroup>>> sources = new ArrayList<>();
        for (String artist : artists) {
            sources.add(content().getSearchObserve(artist,
                    SearchOptions.TYPE_VIDEO | SearchOptions.UPLOAD_DATE_THIS_YEAR | SearchOptions.SORT_BY_UPLOAD_DATE));
        }
        collectVideos(sources, 12_000, lists -> {
            List<List<Video>> theirs = new ArrayList<>();
            for (int i = 0; i < lists.size(); i++) {
                List<Video> songs = new ArrayList<>();
                for (Video video : lists.get(i)) {
                    if (playableSong(video) && fromArtist(video, artists.get(i)) && MusicLists.looksLikeSong(this, video)) {
                        songs.add(video);
                    }
                }
                theirs.add(songs);
            }
            done.onItems(videoItems(listKey, roundRobin(theirs, perArtist, max, video -> true), groupTitle));
        });
    }

    private static boolean fromArtist(Video video, String artist) {
        return MusicArt.similar(MusicArt.cleanAuthor(video.getAuthor()), artist)
                || MusicArt.similar(MusicArt.guess(video.getTitle(), video.getAuthor()).artist, artist);
    }

    private interface VideoFilter {
        boolean accept(Video video);
    }

    /** One from each list in turn (so no list fills everything), without repeating songs. */
    private static List<Video> roundRobin(List<List<Video>> lists, int perList, int max, VideoFilter filter) {
        List<Video> picked = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int[] next = new int[lists.size()];
        int[] taken = new int[lists.size()];
        boolean more = true;
        while (more && picked.size() < max) {
            more = false;
            for (int i = 0; i < lists.size() && picked.size() < max; i++) {
                List<Video> list = lists.get(i);
                while (next[i] < list.size() && taken[i] < perList) {
                    Video video = list.get(next[i]++);
                    if (filter.accept(video) && seen.add(video.videoId)) {
                        picked.add(video);
                        taken[i]++;
                        more = true;
                        break;
                    }
                }
            }
        }
        return picked;
    }

    private final java.util.Set<String> mSavedIds = new java.util.HashSet<>();

    /** The song playing goes to «Mis favoritas» on YouTube (created the first time). From the car. */
    private void saveToFavourites() {
        Video video = mPlayer.isActive() ? mPlayer.current() : null;
        if (video == null || video.videoId == null || mSavedIds.contains(video.videoId)) {
            return;
        }
        String videoId = video.videoId;
        String profileId = profile().id;
        com.liskovsoft.mediaserviceinterfaces.MediaItemService items = YouTubeServiceManager.instance().getMediaItemService();
        Runnable saved = () -> {
            mSavedIds.add(videoId);
            updateState();
            listsChanged();
        };
        mActions.add(items.getPlaylistsInfoObserve(videoId).subscribe(infos -> mHandler.post(() -> {
            if (infos != null) {
                for (com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo info : infos) {
                    if (info != null && FAVOURITES.equalsIgnoreCase(info.getTitle())) {
                        if (info.isSelected()) {
                            saved.run();
                        } else {
                            mActions.add(items.addToPlaylistObserve(info.getPlaylistId(), videoId).subscribe(
                                    ignored -> { }, error -> mHandler.post(() -> savingFailed(error)), () -> mHandler.post(saved)));
                        }
                        return;
                    }
                }
            }
            MusicLists.rememberCreated(this, profileId, FAVOURITES);
            mActions.add(items.createPlaylistObserve(FAVOURITES, videoId).subscribe(
                    ignored -> { }, error -> mHandler.post(() -> savingFailed(error)), () -> mHandler.post(saved)));
        }), error -> mHandler.post(() -> savingFailed(error))));
    }

    private void savingFailed(Throwable error) {
        es.myfamilytube.FamilyDiagnostics.instance(this).log("Música: no se pudo guardar en " + FAVOURITES + ": " + error);
    }

    /** Lists were created, renamed, deleted or changed: they are loaded again. */
    private void listsChanged() {
        List<String> stale = new ArrayList<>();
        for (String id : mChildren.keySet()) {
            if (id.startsWith(PLAYLIST)) {
                stale.add(id);
            }
        }
        stale.add(TAB_LISTS);
        stale.add(TAB_FOR_YOU);
        stale.add(HIDDEN_LISTS);
        for (String id : stale) {
            mChildren.remove(id);
            mChildrenTime.remove(id);
            notifyChildrenChanged(id);
        }
    }

    /** From the phone: a list is hidden from the music, or shown again. */
    private void setListIsMusic(String playlistId, boolean music) {
        if (TextUtils.isEmpty(playlistId)) {
            return;
        }
        MusicLists.setManual(this, profile().id, playlistId, music);
        for (String id : new String[]{TAB_LISTS, TAB_FOR_YOU, HIDDEN_LISTS}) {
            mChildren.remove(id);
            mChildrenTime.remove(id);
            notifyChildrenChanged(id);
        }
    }

    // ---------------------------------------------------------------- screens made of several shelves

    /** A shelf: songs already known on the phone, or something asked to YouTube. */
    private static final class Part {
        final String title;
        final List<MediaItem> local;
        final Observable<List<MediaGroup>> rows;
        final int max;
        final Loader loader;

        private Part(String title, List<MediaItem> local, Observable<List<MediaGroup>> rows, int max, Loader loader) {
            this.title = title;
            this.local = local;
            this.rows = rows;
            this.max = max;
            this.loader = loader;
        }

        /** Built by the app itself (recommended, news, music lists). */
        static Part custom(Loader loader) {
            return new Part(null, null, null, 0, loader);
        }

        static Part local(List<MediaItem> items) {
            return new Part(null, items, null, 0, null);
        }

        static Part rows(String title, Observable<List<MediaGroup>> rows, int max) {
            return new Part(title, null, rows, max, null);
        }
    }

    private interface Loader {
        void load(Done done);
    }

    private static Observable<List<MediaGroup>> asRows(Observable<MediaGroup> group) {
        return group.map(one -> {
            List<MediaGroup> list = new ArrayList<>();
            list.add(one);
            return list;
        });
    }

    /** All the shelves at once (in order); after 15 s, whatever has arrived. Exactly one answer. */
    private void loadParts(String parentId, List<Part> parts, Done done) {
        Done once = once(done);
        List<List<MediaItem>> results = new ArrayList<>();
        int[] waiting = {0};
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            results.add(part.rows == null && part.loader == null ? part.local : null);
        }
        Runnable finish = () -> {
            List<MediaItem> all = new ArrayList<>();
            for (List<MediaItem> part : results) {
                if (part != null) {
                    for (MediaItem item : part) {
                        if (all.size() < MAX_SCREEN) {
                            all.add(item);
                        }
                    }
                }
            }
            once.onItems(all);
        };
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            if (part.rows == null && part.loader == null) {
                continue;
            }
            waiting[0]++;
            int index = i;
            if (part.loader != null) {
                part.loader.load(items -> {
                    if (results.get(index) == null) {
                        results.set(index, items != null ? items : new ArrayList<>());
                        if (--waiting[0] == 0) {
                            finish.run();
                        }
                    }
                });
                continue;
            }
            Runnable failed = () -> {
                if (results.get(index) == null) {
                    results.set(index, new ArrayList<>());
                    if (--waiting[0] == 0) {
                        finish.run();
                    }
                }
            };
            mActions.add(part.rows.subscribe(
                    groups -> mHandler.post(() -> {
                        if (results.get(index) == null) {
                            List<MediaItem> items;
                            try {
                                items = fromRows(parentId + "#" + index, groups, part.title, part.max);
                            } catch (RuntimeException e) {
                                items = new ArrayList<>();
                            }
                            results.set(index, items);
                            if (--waiting[0] == 0) {
                                finish.run();
                            }
                        }
                    }),
                    error -> mHandler.post(failed),
                    () -> mHandler.post(failed)));
        }
        if (waiting[0] == 0) {
            finish.run();
        } else {
            mHandler.postDelayed(finish, 15_000);
        }
    }

    /** «Para ti»: what was listened to lately, mixes of the favourite artists, the lists and YouTube Music. */
    private List<Part> forYou() {
        MusicTaste taste = taste();
        List<Part> parts = new ArrayList<>();
        parts.add(Part.local(localSongs(TAB_FOR_YOU + "#recent", taste.recent(15), "Escuchado recientemente")));
        if (!taste.isEmpty()) {
            parts.add(Part.custom(done -> recommended(TAB_FOR_YOU + "#rec", done)));
            List<String> favourite = new ArrayList<>();
            for (MusicTaste.Entry artist : taste.topArtists(6)) {
                favourite.add(artist.name);
            }
            parts.add(Part.custom(done -> news(TAB_FOR_YOU + "#new", favourite, 3, 18, "Novedades de tus artistas", done)));
        }

        List<MediaItem> mixes = new ArrayList<>();
        for (MusicTaste.Entry artist : taste.topArtists(6)) {
            MusicTaste.Song top = artist.songs.get(0);
            mixes.add(browsable(MIX + top.videoId, "Mezcla de " + artist.name, "Canciones parecidas",
                    CarArtProvider.uriForSong(this, toVideo(top)), "Tus mezclas", STYLE_LIST));
        }
        parts.add(Part.local(mixes));

        List<MediaItem> styles = new ArrayList<>();
        for (MusicTaste.Entry genre : taste.topGenres(6)) {
            styles.add(browsable(GENRE + Uri.encode(genre.name), genre.name, songsText(genre.songs.size()),
                    CarArtProvider.uriForSong(this, toVideo(genre.songs.get(0))), "Tus estilos", STYLE_LIST));
        }
        parts.add(Part.local(styles));

        List<MediaItem> artists = new ArrayList<>();
        for (MusicTaste.Entry artist : taste.topArtists(10)) {
            artists.add(browsable(ARTIST + Uri.encode(artist.name), artist.name, "Artista",
                    CarArtProvider.uriForSong(this, toVideo(artist.songs.get(0))), "Tus artistas", STYLE_LIST));
        }
        parts.add(Part.local(artists));

        parts.add(Part.custom(done -> musicPlaylists(false, "Tus listas", MAX_PER_SHELF, done)));
        parts.add(Part.rows(null, content().getMusicObserve(), MAX_ITEMS));
        return parts;
    }

    /** An artist: your favourite songs, their albums you listen to, and more from YouTube. */
    private List<Part> artistParts(String name) {
        MusicTaste taste = taste();
        List<Part> parts = new ArrayList<>();
        List<MusicTaste.Song> songs = taste.songsOf(MusicTaste.KIND_ARTIST, name);
        parts.add(Part.local(localSongs(ARTIST + name + "#fav", songs.subList(0, Math.min(15, songs.size())), "Tus favoritas")));

        List<MediaItem> more = new ArrayList<>();
        if (!songs.isEmpty()) {
            more.add(browsable(MIX + songs.get(0).videoId, "Mezcla de " + name, "Canciones parecidas",
                    CarArtProvider.uriForSong(this, toVideo(songs.get(0))), "Para seguir escuchando", STYLE_LIST));
        }
        List<String> albums = new ArrayList<>();
        for (MusicTaste.Song song : songs) {
            if (!TextUtils.isEmpty(song.album) && !albums.contains(song.album)) {
                albums.add(song.album);
                more.add(browsable(albumId(name, song.album), song.album, "Álbum",
                        CarArtProvider.uriForSong(this, toVideo(song)), "Para seguir escuchando", STYLE_LIST));
            }
        }
        parts.add(Part.local(more));
        List<String> one = new ArrayList<>();
        one.add(name);
        parts.add(Part.custom(done -> news(ARTIST + name + "#new", one, 10, 10, "Lo último de " + name, done)));
        parts.add(Part.rows("Más de " + name, content().getSearchObserve(name, SearchOptions.TYPE_VIDEO), 25));
        parts.add(Part.rows("Listas con " + name, content().getSearchObserve(name, SearchOptions.TYPE_PLAYLIST), 8));
        return parts;
    }

    /** A kind of music: your songs, your artists, and new music of that kind. */
    private List<Part> genreParts(String genre) {
        MusicTaste taste = taste();
        List<Part> parts = new ArrayList<>();
        List<MusicTaste.Song> songs = taste.songsOf(MusicTaste.KIND_GENRE, genre);
        parts.add(Part.local(localSongs(GENRE + genre + "#fav", songs.subList(0, Math.min(15, songs.size())), "Tus favoritas")));
        List<MediaItem> artists = new ArrayList<>();
        for (MusicTaste.Entry artist : taste.artistsOfGenre(genre, 10)) {
            artists.add(browsable(ARTIST + Uri.encode(artist.name), artist.name, "Artista",
                    CarArtProvider.uriForSong(this, toVideo(artist.songs.get(0))), "Tus artistas", STYLE_LIST));
        }
        parts.add(Part.local(artists));
        parts.add(Part.rows("Descubre " + genre, content().getSearchObserve(genre + " música", SearchOptions.TYPE_VIDEO), 25));
        parts.add(Part.rows("Listas de " + genre, content().getSearchObserve(genre + " playlist", SearchOptions.TYPE_PLAYLIST), 8));
        return parts;
    }

    /** An album: the songs you know and the whole album from YouTube. */
    private List<Part> albumParts(String artist, String album) {
        MusicTaste taste = taste();
        List<Part> parts = new ArrayList<>();
        List<MusicTaste.Song> known = new ArrayList<>();
        for (MusicTaste.Song song : taste.songsOf(MusicTaste.KIND_ALBUM, album)) {
            if (TextUtils.isEmpty(artist) || MusicArt.similar(song.artist, artist)) {
                known.add(song);
            }
        }
        parts.add(Part.local(localSongs(ALBUM + album + "#fav", known, "Has escuchado")));
        String query = (TextUtils.isEmpty(artist) ? "" : artist + " ") + album;
        parts.add(Part.rows("Álbum completo", content().getSearchObserve(query + " album", SearchOptions.TYPE_PLAYLIST), 6));
        parts.add(Part.rows("Canciones", content().getSearchObserve(query, SearchOptions.TYPE_VIDEO), 20));
        return parts;
    }

    private static String songsText(int count) {
        return count == 1 ? "1 canción escuchada" : count + " canciones escuchadas";
    }

    private static Video toVideo(MusicTaste.Song song) {
        Video video = new Video();
        video.videoId = song.videoId;
        video.title = song.title;
        video.author = song.author;
        video.channelId = song.channelId;
        video.cardImageUrl = song.image;
        return video;
    }

    /** Songs saved on the phone (history, favourites): a shelf whose songs are its queue. */
    private List<MediaItem> localSongs(String listKey, List<MusicTaste.Song> songs, String groupTitle) {
        List<MediaItem> items = new ArrayList<>();
        List<Video> queue = new ArrayList<>();
        for (MusicTaste.Song song : songs) {
            Video video = toVideo(song);
            if (!ContentGate.allows(video)) {
                continue;
            }
            queue.add(video);
            items.add(playable(listKey, video, groupTitle));
        }
        mLists.put(listKey, queue);
        return items;
    }

    /** A strange item from YouTube must never close the app: that list just comes out shorter or empty. */
    private List<MediaItem> safeRows(String parentId, List<MediaGroup> groups) {
        try {
            return fromRows(parentId, groups, null, MAX_ITEMS);
        } catch (RuntimeException e) {
            es.myfamilytube.FamilyDiagnostics.instance(this).log("Android Auto: error al preparar «" + parentId + "»: " + e);
            return new ArrayList<>();
        }
    }

    private static Done once(Done done) {
        final boolean[] sent = {false};
        return items -> {
            if (!sent[0]) {
                sent[0] = true;
                done.onItems(items);
            }
        };
    }

    /** The first list with songs that YouTube sends with a video (a playlist or a mix). */
    private static Observable<MediaGroup> firstList(com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata metadata) {
        if (metadata != null && metadata.getSuggestions() != null) {
            for (MediaGroup group : metadata.getSuggestions()) {
                if (group != null && group.getMediaItems() != null && !group.getMediaItems().isEmpty()) {
                    return Observable.just(group);
                }
            }
        }
        return Observable.empty();
    }

    /** YouTube's mix of a song: songs similar to it, like a radio. */
    private Observable<MediaGroup> mixObserve(String videoId) {
        return YouTubeServiceManager.instance().getMediaItemService()
                .getMetadataObserve(videoId, "RD" + videoId, 0, null)
                .flatMap(CarMusicService::firstList);
    }

    /** A playlist's videos, loaded the same way the phone and TV screens do it. */
    private Observable<MediaGroup> playlistObserve(Video item) {
        if (item.mediaItem == null) {
            item.mediaItem = com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem.from(item);
        }
        if (item.hasNestedItems() || item.isChannel()) {
            return content().getGroupObserve(item.mediaItem);
        }
        if (item.hasReloadPageKey()) {
            return content().getGroupObserve(item.getReloadPageKey());
        }
        return YouTubeServiceManager.instance().getMediaItemService()
                .getMetadataObserve(item.videoId, item.playlistId, 0, item.playlistParams)
                .flatMap(CarMusicService::firstList);
    }

    private List<MediaItem> kidChannels() {
        List<MediaItem> items = new ArrayList<>();
        for (Entry<String, String> entry : profile().approvedChannels.entrySet()) {
            Video channel = new Video();
            channel.channelId = entry.getKey();
            channel.title = entry.getValue();
            items.add(folder(CHANNEL + entry.getKey(), channel, null));
        }
        return items;
    }

    /**
     * Shelves under their titles; the videos of each shelf become its queue. With a title,
     * all the groups are one shelf with that title (at most max items, only songs, no long videos).
     */
    private List<MediaItem> fromRows(String parentId, List<MediaGroup> groups, String forcedTitle, int max) {
        List<MediaItem> items = new ArrayList<>();
        if (groups == null) {
            return items;
        }
        boolean showTitles = forcedTitle != null || groups.size() > 1;
        int shelf = 0;
        List<Video> sharedQueue = forcedTitle != null ? new ArrayList<>() : null;
        int sharedCount = 0;
        for (MediaGroup group : groups) {
            if (group == null || group.getMediaItems() == null || items.size() >= MAX_ITEMS) {
                continue;
            }
            String groupTitle = forcedTitle != null ? forcedTitle : (showTitles ? group.getTitle() : null);
            if (group.getTitle() != null && group.getTitle().trim().equalsIgnoreCase("shorts") && !ContentGate.allowsShorts()) {
                continue;
            }
            String listKey = forcedTitle != null ? parentId : parentId + "#" + shelf++;
            List<Video> queue = sharedQueue != null ? sharedQueue : new ArrayList<>();
            int count = forcedTitle != null ? sharedCount : 0;
            int limit = forcedTitle != null ? max : (showTitles ? MAX_PER_SHELF : max);
            for (com.liskovsoft.mediaserviceinterfaces.data.MediaItem mediaItem : group.getMediaItems()) {
                if (items.size() >= MAX_ITEMS || count >= limit) {
                    break;
                }
                Video video = Video.from(mediaItem);
                if (video == null) {
                    continue;
                }
                if (video.hasVideo()) {
                    if (video.isLive || video.isShorts || !ContentGate.allows(video)) {
                        continue;
                    }
                    if (forcedTitle != null && video.getDurationMs() > MAX_SONG_MS) {
                        continue; // a long video, not a song
                    }
                    queue.add(video);
                    items.add(playable(listKey, video, groupTitle));
                    count++;
                } else if (video.hasPlaylist()) {
                    items.add(folder(PLAYLIST + video.playlistId, video, groupTitle));
                    count++;
                } else if (video.channelId != null && (video.isChannel() || !profile().isKid)) {
                    if (!profile().isKid || profile().isChannelApproved(video.channelId)) {
                        items.add(folder(CHANNEL + video.channelId, video, groupTitle));
                        count++;
                    }
                }
            }
            if (forcedTitle != null) {
                sharedCount = count;
            }
            mLists.put(listKey, queue);
        }
        return items;
    }

    /** A song: with the names and the cover from MusicBrainz when they are known. */
    private MediaDescriptionCompat songDescription(String mediaId, Video video, Bundle extras) {
        return songDescription(mediaId, video, extras, false);
    }

    /** withDuration: «Artista · 3:45» (search results). */
    private MediaDescriptionCompat songDescription(String mediaId, Video video, Bundle extras, boolean withDuration) {
        MusicArt.Info info = MusicArt.cached(this, video.videoId);
        String title = info != null && info.track != null ? info.track : video.getTitle();
        String artist = info != null && info.artist != null ? info.artist : MusicArt.cleanAuthor(video.getAuthor());
        String subtitle = artist;
        long duration = video.getDurationMs();
        if (withDuration && duration > 0) {
            subtitle = (TextUtils.isEmpty(artist) ? "" : artist + " · ") + formatDuration(duration);
        }
        return new MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIconUri(CarArtProvider.uriForSong(this, video))
                .setExtras(extras)
                .build();
    }

    private static String formatDuration(long ms) {
        long total = ms / 1000;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        return hours > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private static final long SHORT_MS = 60_000;

    private List<MediaItem> safeSearch(String parentId, List<MediaGroup> groups) {
        try {
            return searchResults(parentId, groups);
        } catch (RuntimeException e) {
            es.myfamilytube.FamilyDiagnostics.instance(this).log("Música: error al preparar la búsqueda: " + e);
            return new ArrayList<>();
        }
    }

    /**
     * Search results, a bit filtered: the songs first (with their length), then lists and
     * channels, and at the end «Otros resultados»: what lasts less than a minute.
     */
    private List<MediaItem> searchResults(String parentId, List<MediaGroup> groups) {
        List<Video> songs = new ArrayList<>();
        List<Video> others = new ArrayList<>();
        List<MediaItem> folders = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        FamilyProfile profile = profile();
        for (Video video : videosOf(groups)) {
            if (songs.size() + others.size() + folders.size() >= MAX_ITEMS) {
                break;
            }
            if (video.hasVideo()) {
                if (video.isLive || !ContentGate.allows(video) || !seen.add(video.videoId)
                        || (video.isShorts && !ContentGate.allowsShorts())) {
                    continue;
                }
                long duration = video.getDurationMs();
                if (video.isShorts || (duration > 0 && duration < SHORT_MS)) {
                    others.add(video);
                } else {
                    songs.add(video);
                }
            } else if (video.hasPlaylist()) {
                if (seen.add("pl:" + video.playlistId)) {
                    folders.add(folder(PLAYLIST + video.playlistId, video, "Listas y canales"));
                }
            } else if (video.channelId != null && (!profile.isKid || profile.isChannelApproved(video.channelId))) {
                if (seen.add("ch:" + video.channelId)) {
                    folders.add(folder(CHANNEL + video.channelId, video, "Listas y canales"));
                }
            }
        }
        List<MediaItem> items = new ArrayList<>();
        items.addAll(searchSongs(parentId + "#songs", songs, "Canciones"));
        items.addAll(folders);
        items.addAll(searchSongs(parentId + "#others", others, "Otros resultados"));
        return items;
    }

    private List<MediaItem> searchSongs(String listKey, List<Video> videos, String groupTitle) {
        List<MediaItem> items = new ArrayList<>();
        mLists.put(listKey, videos);
        for (Video video : videos) {
            Bundle extras = new Bundle();
            extras.putString(STYLE_GROUP, groupTitle);
            items.add(new MediaItem(songDescription(VIDEO + video.videoId + "@" + listKey, video, extras, true), MediaItem.FLAG_PLAYABLE));
        }
        return items;
    }

    private MediaItem playable(String listKey, Video video, String groupTitle) {
        Bundle extras = new Bundle();
        if (groupTitle != null) {
            extras.putString(STYLE_GROUP, groupTitle);
        }
        return new MediaItem(songDescription(VIDEO + video.videoId + "@" + listKey, video, extras), MediaItem.FLAG_PLAYABLE);
    }

    private MediaItem browsable(String id, String title, String subtitle, Uri icon, String groupTitle, int playableStyle) {
        Bundle extras = new Bundle();
        extras.putInt(STYLE_PLAYABLE, playableStyle);
        if (groupTitle != null) {
            extras.putString(STYLE_GROUP, groupTitle);
        }
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIconUri(icon)
                .setExtras(extras)
                .build();
        return new MediaItem(description, MediaItem.FLAG_BROWSABLE);
    }

    private MediaItem folder(String id, Video video, String groupTitle) {
        mFolders.put(id, video);
        String title = !TextUtils.isEmpty(video.getTitle()) ? video.getTitle() : video.getAuthor();
        return browsable(id, title, id.startsWith(PLAYLIST) ? video.getAuthor() : null,
                CarArtProvider.uriFor(this, video.getCardImageUrl()), groupTitle, STYLE_LIST);
    }

    private void disposeActions() {
        for (Disposable action : mActions) {
            action.dispose();
        }
        mActions.clear();
    }

    // ---------------------------------------------------------------- playback

    private void playFromList(String listKey, String videoId) {
        List<Video> queue = mLists.get(listKey);
        Video start = null;
        if (queue != null) {
            for (Video video : queue) {
                if (videoId.equals(video.videoId)) {
                    start = video;
                    break;
                }
            }
        }
        if (start == null) {
            start = Video.from(videoId);
            queue = new ArrayList<>();
            queue.add(start);
        }
        playQueue(queue, start);
    }

    /** Spotify-like: the rest of the list plays after it, then YouTube's own "up next". */
    private void playQueue(List<Video> queue, Video start) {
        if (FamilyProfiles.instance(this).isTimeUp(profile())) {
            showError("Se ha acabado el tiempo de hoy");
            return;
        }
        if (!ContentGate.allowsPlayback(this, start)) {
            showError("Este vídeo no está permitido en este perfil");
            return;
        }
        mError = null;
        // The phone player and the car share the engine: the car takes it
        PlaybackView current = PlaybackPresenter.instance(this).getView();
        if (current != null && !(current instanceof CarPlayer)) {
            current.finishReally();
        }
        mQueue.clear();
        mQueue.addAll(queue);
        mQueueGeneration++;
        mExtending = false;
        mWaitingForMore = false;
        int index = mQueue.indexOf(start);
        mSkipsInARow = 0;
        mSession.setActive(true);
        playAt(index >= 0 ? index : 0);
    }

    // ---------------------------------------------------------------- the car's own list

    /** The list being played (the rest of the chosen list, then YouTube's "up next"). */
    private final List<Video> mQueue = new ArrayList<>();
    private int mIndex = -1;
    private CarSongCache mCache;
    private String mRecordedId;
    private final java.util.concurrent.ExecutorService mWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final int SAVE_AHEAD = 3;
    private static final int KEEP_BEHIND = 4;

    private void playAt(int index) {
        if (index < 0 || index >= mQueue.size()) {
            stopPlayback(false);
            return;
        }
        mIndex = index;
        Video video = mQueue.get(index);
        if (FamilyProfiles.instance(this).isTimeUp(profile())) {
            showError("Se ha acabado el tiempo de hoy");
            return;
        }
        if (!ContentGate.allowsPlayback(this, video)) {
            onFinishRequested(); // not for this profile: the next one
            return;
        }
        mError = null;
        File saved = mCache.get(video.videoId);
        if (saved != null) {
            // Already on the phone: plays even without coverage
            mPlayer.playLocal(video, saved);
        } else {
            // The engine plays only this one; at its end the car's list chooses the next
            Playlist playlist = Playlist.instance();
            playlist.clear();
            playlist.add(video);
            playlist.setCurrent(video);
            video.fromQueue = true;
            mPlayer.play(video);
            mPlayer.setPlayWhenReady(true);
        }
        updateState();
        updateSessionQueue();
        updateCache();
        lookUpSong(video);
        for (int i = mIndex + 1; i < Math.min(mQueue.size(), mIndex + 4); i++) {
            lookUpSong(mQueue.get(i)); // the next covers ready before they play (1 search per second)
        }
    }

    /** Album, cover and clean names from MusicBrainz for the song now playing (and the next ones) (shown when they arrive). */
    private void lookUpSong(Video video) {
        if (video == null || video.videoId == null || MusicArt.cached(this, video.videoId) != null) {
            return;
        }
        String videoId = video.videoId;
        String title = video.getTitle();
        String author = video.getAuthor();
        mWorker.execute(() -> {
            if (MusicArt.lookup(this, videoId, title, author) != null) {
                mHandler.post(() -> {
                    Video current = mPlayer.isActive() ? mPlayer.current() : null;
                    if (current != null && videoId.equals(current.videoId)) {
                        updateState();
                    }
                });
            }
        });
    }

    /** A song listened to for about half a minute counts for the tabs («Para ti», artists, styles…). */
    private void recordListening() {
        Video video = mPlayer.isActive() ? mPlayer.current() : null;
        if (video == null || video.videoId == null || video.videoId.equals(mRecordedId) || mPlayer.uiPosition() < 25_000) {
            return;
        }
        mRecordedId = video.videoId;
        String videoId = video.videoId;
        String title = video.getTitle();
        String author = video.getAuthor();
        String channelId = video.channelId;
        String image = video.getCardImageUrl();
        MusicTaste taste = taste();
        mWorker.execute(() -> taste.record(videoId, title, author, channelId, image, MusicArt.lookup(this, videoId, title, author)));
    }

    /** The queue, for the car's «A continuación» and the phone. */
    private void updateSessionQueue() {
        List<MediaSessionCompat.QueueItem> items = new ArrayList<>();
        int from = Math.max(0, mIndex - 5);
        int to = Math.min(mQueue.size(), from + 60);
        for (int i = from; i < to; i++) {
            Video video = mQueue.get(i);
            if (video.videoId == null) {
                continue;
            }
            items.add(new MediaSessionCompat.QueueItem(songDescription(VIDEO + video.videoId + "@queue", video, null), i));
        }
        mSession.setQueue(items);
        mSession.setQueueTitle("A continuación");
    }

    /** Saves the next songs while there is coverage and keeps only the last few played. */
    private void updateCache() {
        List<String> keep = new ArrayList<>();
        for (int i = Math.max(0, mIndex - KEEP_BEHIND); i <= mIndex && i < mQueue.size(); i++) {
            keep.add(mQueue.get(i).videoId);
        }
        List<String> ahead = new ArrayList<>();
        for (int i = mIndex + 1; i < mQueue.size() && ahead.size() < SAVE_AHEAD; i++) {
            Video next = mQueue.get(i);
            if (next.videoId != null && !next.isLive && ContentGate.allowsPlayback(this, next)) {
                ahead.add(next.videoId);
            }
        }
        mCache.update(keep, ahead);
        if (mQueue.size() - 1 - mIndex < SAVE_AHEAD) {
            extendQueue(); // the list is ending: add what YouTube plays next
        }
    }

    private boolean mExtending;
    /** The list ended while looking for more songs: play the first one found. */
    private boolean mWaitingForMore;
    private int mQueueGeneration;
    private static final int MAX_SIMILAR = 20;

    /**
     * When the list is ending, songs similar to the last one (YouTube's mix of that song, like
     * a radio), so the music never stops. If there is no mix, the related videos.
     */
    private void extendQueue() {
        if (mExtending || mQueue.isEmpty()) {
            return;
        }
        Video last = mQueue.get(mQueue.size() - 1);
        if (last.videoId == null) {
            return;
        }
        mExtending = true;
        String seed = last.videoId;
        int generation = mQueueGeneration;
        boolean[] handled = {false};
        io.reactivex.functions.Consumer<MediaGroup> onMix = group -> {
            if (handled[0] || generation != mQueueGeneration) {
                return;
            }
            handled[0] = true;
            int added = group != null ? addSimilar(group.getMediaItems()) : 0;
            if (added > 0) {
                extended(added);
            } else {
                relatedSongs(seed, generation);
            }
        };
        mActions.add(mixObserve(seed).subscribe(
                group -> mHandler.post(() -> safeAccept(onMix, group)),
                error -> mHandler.post(() -> safeAccept(onMix, null)),
                () -> mHandler.post(() -> safeAccept(onMix, null))));
    }

    private static void safeAccept(io.reactivex.functions.Consumer<MediaGroup> consumer, MediaGroup group) {
        try {
            consumer.accept(group);
        } catch (Exception e) {
            // nothing more to add
        }
    }

    /** No mix: the videos YouTube suggests next to the song. */
    private void relatedSongs(String seed, int generation) {
        mActions.add(YouTubeServiceManager.instance().getMediaItemService().getMetadataObserve(seed)
                .subscribe(metadata -> mHandler.post(() -> {
                    if (generation != mQueueGeneration) {
                        return;
                    }
                    List<com.liskovsoft.mediaserviceinterfaces.data.MediaItem> found = new ArrayList<>();
                    if (metadata != null && metadata.getNextVideo() != null) {
                        found.add(metadata.getNextVideo());
                    }
                    if (metadata != null && metadata.getSuggestions() != null) {
                        for (MediaGroup group : metadata.getSuggestions()) {
                            if (group != null && group.getMediaItems() != null) {
                                found.addAll(group.getMediaItems());
                            }
                        }
                    }
                    extended(addSimilar(found));
                }), error -> mHandler.post(() -> {
                    if (generation == mQueueGeneration) {
                        extended(0);
                    }
                })));
    }

    private int addSimilar(List<com.liskovsoft.mediaserviceinterfaces.data.MediaItem> items) {
        int added = 0;
        if (items == null) {
            return 0;
        }
        for (com.liskovsoft.mediaserviceinterfaces.data.MediaItem item : items) {
            if (added >= MAX_SIMILAR) {
                break;
            }
            Video video = item != null ? Video.from(item) : null;
            if (video == null || video.videoId == null || video.isLive || video.isShorts || containsVideo(video.videoId)
                    || video.getDurationMs() > MAX_SONG_MS || !ContentGate.allows(video)) {
                continue;
            }
            mQueue.add(video);
            added++;
        }
        return added;
    }

    private void extended(int added) {
        mExtending = false;
        if (added > 0) {
            updateSessionQueue();
            updateCache();
        }
        if (mWaitingForMore) {
            mWaitingForMore = false;
            if (mIndex + 1 < mQueue.size()) {
                playAt(mIndex + 1);
            } else {
                stopPlayback(false);
            }
        }
    }

    private boolean containsVideo(String videoId) {
        for (Video video : mQueue) {
            if (videoId.equals(video.videoId)) {
                return true;
            }
        }
        return false;
    }

    private void next() {
        if (mIndex + 1 < mQueue.size()) {
            playAt(mIndex + 1);
            return;
        }
        // The list has ended: similar songs (it waits for them if they are still on their way)
        mWaitingForMore = true;
        extendQueue();
        if (!mExtending) {
            mWaitingForMore = false;
            stopPlayback(false);
        }
    }

    private void previous() {
        if (mIndex > 0) {
            playAt(mIndex - 1);
        } else if (mPlayer.isActive()) {
            mPlayer.uiSeek(0);
        }
    }

    private void playFromSearch(String query) {
        if (TextUtils.isEmpty(query)) {
            // "Play MyFamilyTube": continue, or start with the music home
            if (mPlayer.uiHasMedia()) {
                mPlayer.uiSetPlayWhenReady(true);
                return;
            }
            load(TAB_HOME, items -> playFirst(items));
            return;
        }
        load(SEARCH + query, this::playFirst);
    }

    private void playFirst(List<MediaItem> items) {
        for (MediaItem item : items) {
            String id = item.getMediaId();
            if (item.isPlayable() && id != null && id.startsWith(VIDEO)) {
                playMediaId(id);
                return;
            }
        }
        showError("No se ha encontrado nada para reproducir");
    }

    private void playMediaId(String mediaId) {
        if (mediaId == null || !mediaId.startsWith(VIDEO)) {
            return;
        }
        String rest = mediaId.substring(VIDEO.length());
        int at = rest.indexOf('@');
        String videoId = at > 0 ? rest.substring(0, at) : rest;
        String listKey = at > 0 ? rest.substring(at + 1) : "";
        playFromList(listKey, videoId);
    }

    private void stopPlayback(boolean release) {
        if (mPlayer.isActive()) {
            mPlayer.uiSetPlayWhenReady(false);
        }
        if (release) {
            mPlayer.release();
        }
        mHandler.removeCallbacks(mTick);
        stopForegroundCompat(true);
        updateState();
    }

    private void showError(String message) {
        mError = message;
        if (mPlayer.isActive()) {
            mPlayer.uiSetPlayWhenReady(false);
        }
        updateState();
    }

    @Override
    public void onStateChanged() {
        updateState();
    }

    @Override
    public void onFinishRequested() {
        // End of a video with nothing queued, or a video the profile can't see: go on with the next one
        // (also when a song can't be loaded, e.g. no coverage: the next one may be saved on the phone)
        if (mSkipsInARow++ < 5) {
            next();
        } else {
            stopPlayback(false);
        }
    }

    /** Kids' time also runs in the car. */
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            int seconds = (int) Math.min(60, Math.max(0, (now - mLastTickMs) / 1000));
            mLastTickMs = now;
            recordListening();
            FamilyProfile current = profile();
            if (current.isKid && mPlayer.isActive() && mPlayer.uiIsPlaying()) {
                FamilyProfiles.instance(CarMusicService.this).addUsedSeconds(current, seconds);
                if (FamilyProfiles.instance(CarMusicService.this).isTimeUp(current)) {
                    showError("Se ha acabado el tiempo de hoy");
                    return;
                }
            }
            mHandler.postDelayed(this, 30_000);
        }
    };

    private void updateState() {
        boolean active = mPlayer.isActive();
        boolean playing = active && mPlayer.uiPlayWhenReady();
        int exoState = active ? mPlayer.uiState() : Player.STATE_IDLE;
        int state;
        if (mError != null) {
            state = PlaybackStateCompat.STATE_ERROR;
        } else if (!active) {
            state = PlaybackStateCompat.STATE_NONE;
        } else if (exoState == Player.STATE_BUFFERING || (exoState == Player.STATE_IDLE && playing)) {
            state = PlaybackStateCompat.STATE_BUFFERING;
        } else if (playing && exoState == Player.STATE_READY) {
            state = PlaybackStateCompat.STATE_PLAYING;
        } else {
            state = PlaybackStateCompat.STATE_PAUSED;
        }
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH | PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM;
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, active ? mPlayer.uiPosition() : 0, playing ? 1.0f : 0f)
                .setBufferedPosition(active ? mPlayer.uiBuffered() : 0)
                .setActiveQueueItemId(mIndex >= 0 ? mIndex : MediaSessionCompat.QueueItem.UNKNOWN_ID);
        if (mError != null) {
            builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, mError);
        }
        Video playingNow = active ? mPlayer.current() : null;
        if (playingNow != null && playingNow.videoId != null && !profile().isKid && !TextUtils.isEmpty(FamilyAccounts.currentKey())) {
            boolean saved = mSavedIds.contains(playingNow.videoId);
            builder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(ACTION_SAVE_SONG,
                    saved ? "Guardada en " + FAVOURITES : "Guardar en " + FAVOURITES,
                    saved ? R.drawable.music_ic_saved : R.drawable.music_ic_add).build());
        }
        mSession.setPlaybackState(builder.build());

        Video video = active ? mPlayer.current() : null;
        if (video != null) {
            MusicArt.Info info = MusicArt.cached(this, video.videoId);
            String title = info != null && info.track != null ? info.track
                    : (video.getTitleFull() != null ? video.getTitleFull() : video.getTitle());
            String artist = info != null && info.artist != null ? info.artist : MusicArt.cleanAuthor(video.getAuthor());
            Uri art = CarArtProvider.uriForSong(this, video);
            MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, video.videoId)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist);
            if (info != null && info.album != null) {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.album);
                metadata.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, info.album);
            }
            if (info != null && info.genre != null) {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_GENRE, info.genre);
            }
            long duration = mPlayer.uiDuration();
            if (duration > 0) {
                metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
            }
            if (art != null) {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, art.toString());
                metadata.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, art.toString());
            }
            mSession.setMetadata(metadata.build());
        }

        if (playing) {
            if (exoState == Player.STATE_READY) {
                mSkipsInARow = 0;
            }
            startForegroundCompat(video);
            mHandler.removeCallbacks(mTick);
            if (mLastTickMs == 0 || System.currentTimeMillis() - mLastTickMs > 60_000) {
                mLastTickMs = System.currentTimeMillis();
            }
            mHandler.postDelayed(mTick, 30_000);
        } else {
            mHandler.removeCallbacks(mTick);
            stopForegroundCompat(false);
        }
    }

    // ---------------------------------------------------------------- notification (needed to keep playing)

    private void startForegroundCompat(Video video) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Música en el coche", NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
        boolean playing = mPlayer.isActive() && mPlayer.uiPlayWhenReady();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .addAction(R.drawable.mobile_ic_prev, "Anterior", notificationAction(ACTION_PREVIOUS, 1))
                .addAction(playing ? R.drawable.mobile_ic_pause : R.drawable.mobile_ic_play, playing ? "Pausa" : "Reproducir",
                        notificationAction(ACTION_PLAY_PAUSE, 2))
                .addAction(R.drawable.mobile_ic_next, "Siguiente", notificationAction(ACTION_NEXT, 3))
                .setSmallIcon(R.drawable.mobile_ic_play)
                .setContentTitle(songTitle(video))
                .setContentText(songArtist(video))
                .setContentIntent(mSession.getController().getSessionActivity())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
        try {
            if (!mForeground) {
                Intent self = new Intent(this, CarMusicService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(self);
                } else {
                    startService(self);
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            mForeground = true;
        } catch (RuntimeException e) {
            // Android refused: it still plays while Android Auto is connected
        }
    }

    private String songTitle(Video video) {
        if (video == null) {
            return getString(R.string.app_name);
        }
        MusicArt.Info info = MusicArt.cached(this, video.videoId);
        return info != null && info.track != null ? info.track : video.getTitle();
    }

    private String songArtist(Video video) {
        if (video == null) {
            return null;
        }
        MusicArt.Info info = MusicArt.cached(this, video.videoId);
        return info != null && info.artist != null ? info.artist : MusicArt.cleanAuthor(video.getAuthor());
    }

    private void stopForegroundCompat(boolean removeNotification) {
        if (!mForeground) {
            return;
        }
        mForeground = false;
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(removeNotification ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(removeNotification);
        }
    }

    private static final String ACTION_PREVIOUS = "es.myfamilymusic.PREVIOUS";
    private static final String ACTION_PLAY_PAUSE = "es.myfamilymusic.PLAY_PAUSE";
    private static final String ACTION_NEXT = "es.myfamilymusic.NEXT";

    private PendingIntent notificationAction(String action, int code) {
        Intent intent = new Intent(this, CarMusicService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, code, intent, flags);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        // Buttons of the notification (headphones and Bluetooth reach the session directly)
        String action = intent != null ? intent.getAction() : null;
        MediaControllerCompat.TransportControls controls = mSession.getController().getTransportControls();
        if (ACTION_PREVIOUS.equals(action)) {
            controls.skipToPrevious();
        } else if (ACTION_NEXT.equals(action)) {
            controls.skipToNext();
        } else if (ACTION_PLAY_PAUSE.equals(action)) {
            if (mPlayer.isActive() && mPlayer.uiPlayWhenReady()) {
                controls.pause();
            } else {
                controls.play();
            }
        }
        return START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- what the car asks for

    private final class SessionCallback extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            if (mPlayer.isActive() && mPlayer.uiHasMedia()) {
                mError = null;
                mPlayer.uiSetPlayWhenReady(true);
            } else {
                playFromSearch(null);
            }
        }

        @Override
        public void onPause() {
            if (mPlayer.isActive()) {
                mPlayer.uiSetPlayWhenReady(false);
            }
        }

        @Override
        public void onStop() {
            stopPlayback(false);
        }

        @Override
        public void onSkipToNext() {
            mSkipsInARow = 0;
            next();
        }

        @Override
        public void onSkipToPrevious() {
            if (mPlayer.isActive() && mPlayer.uiPosition() > 5000) {
                mPlayer.uiSeek(0);
            } else {
                previous();
            }
        }

        @Override
        public void onSeekTo(long pos) {
            if (mPlayer.isActive()) {
                mPlayer.uiSeek(pos);
                updateState();
            }
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            playMediaId(mediaId);
        }

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            playFromSearch(query);
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            String playlistId = extras != null ? extras.getString(EXTRA_PLAYLIST) : null;
            if (ACTION_HIDE_LIST.equals(action)) {
                setListIsMusic(playlistId, false);
            } else if (ACTION_SHOW_LIST.equals(action)) {
                setListIsMusic(playlistId, true);
            } else if (ACTION_LIST_CREATED.equals(action)) {
                MusicLists.rememberCreated(CarMusicService.this, profile().id, extras != null ? extras.getString(EXTRA_NAME) : null);
                listsChanged();
            } else if (ACTION_LISTS_CHANGED.equals(action)) {
                listsChanged();
            } else if (ACTION_SAVE_SONG.equals(action)) {
                saveToFavourites();
            }
        }

        @Override
        public void onSkipToQueueItem(long id) {
            if (id >= 0 && id < mQueue.size()) {
                mSkipsInARow = 0;
                playAt((int) id);
            }
        }
    }

    static Context appContext() {
        CarMusicService service = sInstance;
        return service != null ? service.getApplicationContext() : null;
    }
}
