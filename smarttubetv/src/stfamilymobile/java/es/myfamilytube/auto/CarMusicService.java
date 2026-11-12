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
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.misc.ContentGate;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

/**
 * MyFamilyTube in Android Auto, like a music app: tabs (Inicio, Tus listas, Recientes, Canales),
 * voice search and the steering wheel buttons. Only the sound is played, with the parental
 * control of the profile chosen on the phone.
 */
public class CarMusicService extends MediaBrowserServiceCompat implements CarPlayer.Listener {
    private static final String ROOT = "root";
    private static final String TAB_HOME = "tab:home";
    private static final String TAB_LISTS = "tab:lists";
    private static final String TAB_RECENT = "tab:recent";
    private static final String TAB_CHANNELS = "tab:channels";
    private static final String PLAYLIST = "pl:";
    private static final String CHANNEL = "ch:";
    private static final String VIDEO = "v:";
    private static final String SEARCH = "search:";

    private static final String STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED";
    private static final String STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    private static final String STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    private static final String STYLE_GROUP = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT";
    private static final String SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED";
    private static final int STYLE_LIST = 1;
    private static final int STYLE_GRID = 2;

    private static final int MAX_ITEMS = 80;
    private static final int MAX_PER_SHELF = 12;
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
        mSession.release();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (!mPlayer.getPlayWhenReady()) {
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

    private List<MediaItem> tabs() {
        List<MediaItem> tabs = new ArrayList<>();
        if (profile().isKid) {
            tabs.add(tab(TAB_HOME, "Inicio", R.drawable.mobile_ic_home, STYLE_GRID, STYLE_GRID));
            if (!profile().approvedChannels.isEmpty()) {
                tabs.add(tab(TAB_CHANNELS, "Tus canales", R.drawable.mobile_ic_subs, STYLE_GRID, STYLE_LIST));
            }
            return tabs;
        }
        tabs.add(tab(TAB_HOME, "Inicio", R.drawable.mobile_ic_home, STYLE_GRID, STYLE_GRID));
        tabs.add(tab(TAB_LISTS, "Tus listas", R.drawable.mobile_ic_library, STYLE_GRID, STYLE_LIST));
        tabs.add(tab(TAB_RECENT, "Recientes", R.drawable.mobile_ic_replay, STYLE_LIST, STYLE_LIST));
        tabs.add(tab(TAB_CHANNELS, "Canales", R.drawable.mobile_ic_subs, STYLE_GRID, STYLE_LIST));
        return tabs;
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

        if (TAB_HOME.equals(parentId)) {
            rows = kid ? content.getKidsHomeObserve() : content.getMusicObserve();
        } else if (TAB_LISTS.equals(parentId) && !kid) {
            grid = content.getPlaylistsObserve();
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
            rows = content.getSearchObserve(parentId.substring(SEARCH.length()));
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

    /** A strange item from YouTube must never close the app: that list just comes out shorter or empty. */
    private List<MediaItem> safeRows(String parentId, List<MediaGroup> groups) {
        try {
            return fromRows(parentId, groups);
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
                .flatMap(metadata -> {
                    if (metadata != null && metadata.getSuggestions() != null) {
                        for (MediaGroup group : metadata.getSuggestions()) {
                            if (group != null && group.getMediaItems() != null && !group.getMediaItems().isEmpty()) {
                                return Observable.just(group);
                            }
                        }
                    }
                    return Observable.empty();
                });
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

    /** Shelves under their titles; the videos of each shelf become its queue. */
    private List<MediaItem> fromRows(String parentId, List<MediaGroup> groups) {
        List<MediaItem> items = new ArrayList<>();
        if (groups == null) {
            return items;
        }
        boolean showTitles = groups.size() > 1;
        int shelf = 0;
        for (MediaGroup group : groups) {
            if (group == null || group.getMediaItems() == null || items.size() >= MAX_ITEMS) {
                continue;
            }
            String groupTitle = showTitles ? group.getTitle() : null;
            if (groupTitle != null && groupTitle.trim().equalsIgnoreCase("shorts") && !ContentGate.allowsShorts()) {
                continue;
            }
            String listKey = parentId + "#" + shelf++;
            List<Video> queue = new ArrayList<>();
            int count = 0;
            for (com.liskovsoft.mediaserviceinterfaces.data.MediaItem mediaItem : group.getMediaItems()) {
                if (items.size() >= MAX_ITEMS || (showTitles && count >= MAX_PER_SHELF)) {
                    break;
                }
                Video video = Video.from(mediaItem);
                if (video == null) {
                    continue;
                }
                if (video.hasVideo()) {
                    if (video.isLive || !ContentGate.allows(video)) {
                        continue;
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
            mLists.put(listKey, queue);
        }
        return items;
    }

    private MediaItem playable(String listKey, Video video, String groupTitle) {
        Bundle extras = new Bundle();
        if (groupTitle != null) {
            extras.putString(STYLE_GROUP, groupTitle);
        }
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(VIDEO + video.videoId + "@" + listKey)
                .setTitle(video.getTitle())
                .setSubtitle(video.getAuthor())
                .setIconUri(CarArtProvider.uriFor(this, video.getCardImageUrl()))
                .setExtras(extras)
                .build();
        return new MediaItem(description, MediaItem.FLAG_PLAYABLE);
    }

    private MediaItem folder(String id, Video video, String groupTitle) {
        mFolders.put(id, video);
        Bundle extras = new Bundle();
        extras.putInt(STYLE_PLAYABLE, STYLE_LIST);
        if (groupTitle != null) {
            extras.putString(STYLE_GROUP, groupTitle);
        }
        String title = !TextUtils.isEmpty(video.getTitle()) ? video.getTitle() : video.getAuthor();
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(id.startsWith(PLAYLIST) ? video.getAuthor() : null)
                .setIconUri(CarArtProvider.uriFor(this, video.getCardImageUrl()))
                .setExtras(extras)
                .build();
        return new MediaItem(description, MediaItem.FLAG_BROWSABLE);
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
        Playlist playlist = Playlist.instance();
        playlist.clear();
        playlist.addAll(queue);
        playlist.setCurrent(start);
        start.fromQueue = true; // keep its place in the queue (the engine would move it to the end)
        mSkipsInARow = 0;
        mSession.setActive(true);
        mPlayer.play(start);
        mPlayer.setPlayWhenReady(true);
        updateState();
    }

    private void playFromSearch(String query) {
        if (TextUtils.isEmpty(query)) {
            // "Play MyFamilyTube": continue, or start with the music home
            if (mPlayer.containsMedia()) {
                mPlayer.setPlayWhenReady(true);
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
            mPlayer.setPlayWhenReady(false);
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
            mPlayer.setPlayWhenReady(false);
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
        if (mPlayer.isActive() && mSkipsInARow++ < 5) {
            mPlayer.next();
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
            FamilyProfile current = profile();
            if (current.isKid && mPlayer.isActive() && mPlayer.isPlaying()) {
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
        boolean playing = active && mPlayer.getPlayWhenReady();
        int exoState = active ? mPlayer.getPlaybackState() : Player.STATE_IDLE;
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
                | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH;
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, active ? mPlayer.getPositionMs() : 0, playing ? 1.0f : 0f)
                .setBufferedPosition(active ? mPlayer.getBufferedPositionMs() : 0);
        if (mError != null) {
            builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, mError);
        }
        mSession.setPlaybackState(builder.build());

        Video video = active ? mPlayer.getVideo() : null;
        if (video != null) {
            String title = video.getTitleFull() != null ? video.getTitleFull() : video.getTitle();
            Uri art = CarArtProvider.uriFor(this, video.getCardImageUrl());
            MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, video.videoId)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, video.getAuthor())
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, video.getAuthor());
            long duration = mPlayer.getDurationMs();
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
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.mobile_ic_play)
                .setContentTitle(video != null ? video.getTitle() : getString(R.string.app_name))
                .setContentText(video != null ? video.getAuthor() : null)
                .setContentIntent(mSession.getController().getSessionActivity())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mSession.getSessionToken()))
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- what the car asks for

    private final class SessionCallback extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            if (mPlayer.isActive() && mPlayer.containsMedia()) {
                mError = null;
                mPlayer.setPlayWhenReady(true);
            } else {
                playFromSearch(null);
            }
        }

        @Override
        public void onPause() {
            if (mPlayer.isActive()) {
                mPlayer.setPlayWhenReady(false);
            }
        }

        @Override
        public void onStop() {
            stopPlayback(false);
        }

        @Override
        public void onSkipToNext() {
            mSkipsInARow = 0;
            mPlayer.next();
        }

        @Override
        public void onSkipToPrevious() {
            if (mPlayer.isActive() && mPlayer.getPositionMs() > 5000) {
                mPlayer.setPositionMs(0);
            } else {
                mPlayer.previous();
            }
        }

        @Override
        public void onSeekTo(long pos) {
            if (mPlayer.isActive()) {
                mPlayer.setPositionMs(pos);
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
    }

    static Context appContext() {
        CarMusicService service = sInstance;
        return service != null ? service.getApplicationContext() : null;
    }
}
