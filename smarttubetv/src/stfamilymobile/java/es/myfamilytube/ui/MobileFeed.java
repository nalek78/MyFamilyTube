package es.myfamilytube.ui;

import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.misc.ContentGate;

import java.util.ArrayList;
import java.util.List;

/**
 * The videos of one phone list, kept as the engine sends them (groups that grow and change)
 * and flattened into "title, video, video…" for the screen.
 * Shorts and anything the profile's parental control doesn't allow never get in.
 */
final class MobileFeed {
    /** A row of the engine (a shelf on Home, a block of search results…). */
    private static final class Block {
        final int id;
        String title;
        final List<Video> videos = new ArrayList<>();

        Block(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    /** A section title in the flattened list. */
    static final class Header {
        final String title;

        Header(String title) {
            this.title = title;
        }
    }

    private final List<Block> mBlocks = new ArrayList<>();
    /** Things shown above the videos (e.g. a kid's approved channels). */
    private final List<Object> mTop = new ArrayList<>();
    private boolean mShowTitles = true;

    void setShowTitles(boolean show) {
        mShowTitles = show;
    }

    void setTop(List<Object> items) {
        mTop.clear();
        if (items != null) {
            mTop.addAll(items);
        }
    }

    void clear() {
        mBlocks.clear();
    }

    /** Drops one whole row of the engine. */
    void removeBlock(int id) {
        Block block = find(id);
        if (block != null) {
            mBlocks.remove(block);
        }
    }

    boolean isEmpty() {
        for (Block block : mBlocks) {
            if (!block.videos.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The last video, to ask the engine for more when the user gets near the end. */
    Video lastVideo() {
        for (int i = mBlocks.size() - 1; i >= 0; i--) {
            List<Video> videos = mBlocks.get(i).videos;
            if (!videos.isEmpty()) {
                return videos.get(videos.size() - 1);
            }
        }
        return null;
    }

    private Block find(int id) {
        for (Block block : mBlocks) {
            if (block.id == id) {
                return block;
            }
        }
        return null;
    }

    private static List<Video> allowed(List<Video> videos) {
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

    /** Applies one update from the engine, exactly as the TV screens do. */
    void apply(VideoGroup group) {
        if (group == null || group.isShorts()) {
            return;
        }
        int action = group.getAction();
        Block block = find(group.getId());

        if (action == VideoGroup.ACTION_REMOVE || action == VideoGroup.ACTION_REMOVE_AUTHOR) {
            if (block != null) {
                for (Video removed : group.getVideos()) {
                    for (int i = block.videos.size() - 1; i >= 0; i--) {
                        Video video = block.videos.get(i);
                        boolean same = action == VideoGroup.ACTION_REMOVE_AUTHOR
                                ? video.channelId != null && video.channelId.equals(removed.channelId)
                                : video.equals(removed);
                        if (same) {
                            block.videos.remove(i);
                        }
                    }
                }
            }
            return;
        }

        if (action == VideoGroup.ACTION_REPLACE && group.getPosition() == -1) {
            mBlocks.clear(); // a full (re)load starts
            block = null;
        }

        // A whole "Shorts" shelf goes, even if its videos aren't marked one by one
        boolean shortsShelf = group.getTitle() != null && group.getTitle().trim().equalsIgnoreCase("shorts");
        List<Video> videos = shortsShelf ? new ArrayList<>() : allowed(group.getVideos());

        if (block == null) {
            if (videos.isEmpty()) {
                return;
            }
            block = new Block(group.getId(), group.getTitle());
            int position = group.getPosition();
            if (position < 0 || position > mBlocks.size()) {
                mBlocks.add(block);
            } else {
                mBlocks.add(position, block);
            }
        } else if (action == VideoGroup.ACTION_REPLACE || action == VideoGroup.ACTION_SYNC) {
            block.videos.clear();
        }
        if (!TextUtils.isEmpty(group.getTitle())) {
            block.title = group.getTitle();
        }
        block.videos.addAll(videos);
    }

    /** Titles and videos in screen order. */
    List<Object> flatten() {
        List<Object> items = new ArrayList<>(mTop);
        for (Block block : mBlocks) {
            if (block.videos.isEmpty()) {
                continue;
            }
            if (mShowTitles && !TextUtils.isEmpty(block.title)) {
                items.add(new Header(block.title));
            }
            items.addAll(block.videos);
        }
        return items;
    }
}
