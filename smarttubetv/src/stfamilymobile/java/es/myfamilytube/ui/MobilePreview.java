package es.myfamilytube.ui;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;

/**
 * Phone previews, like YouTube: the first video whose picture is fully on screen plays inside
 * its thumbnail without sound (the speaker on it turns the sound on). It stops when it leaves
 * the screen or another video becomes the first one. Tapping the video opens it from that moment.
 */
final class MobilePreview {
    /** Sound on/off is kept while the app is open, like YouTube. */
    private static boolean sMuted = true;

    private final Activity mActivity;
    private final FamilyPreview mPreview;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Rect mRect = new Rect();
    private final Rect mListRect = new Rect();
    private RecyclerView mList;
    private View mCard;
    private Video mVideo;
    private boolean mPaused;
    /** Something on top of the list (welcome, profiles, settings): no preview. */
    private java.util.concurrent.Callable<Boolean> mCanPlay;

    private final Runnable mCheck = this::check;

    MobilePreview(Activity activity) {
        mActivity = activity;
        mPreview = new FamilyPreview(activity, seconds -> {
            // Previews count towards a kid's daily time
            FamilyProfile profile = FamilyProfiles.instance(activity).getCurrent();
            if (profile.isKid) {
                FamilyProfiles.instance(activity).addUsedSeconds(profile, seconds);
            }
        });
        mPreview.setMuted(sMuted);
    }

    /** Watches the list: checks when scrolling stops, and stops at once if the card scrolls away. */
    void setCanPlay(java.util.concurrent.Callable<Boolean> canPlay) {
        mCanPlay = canPlay;
    }

    private boolean canPlay() {
        try {
            return mCanPlay == null || mCanPlay.call();
        } catch (Exception e) {
            return true;
        }
    }

    void attach(RecyclerView list) {
        mList = list;
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    refresh();
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (mCard != null && !isFullyVisible(mCard)) {
                    stop();
                }
                if (recyclerView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                    refresh(); // moved by code (new items, jump to top)
                }
            }
        });
        list.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View view) {
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
                if (view == mCard) {
                    stop();
                }
            }
        });
    }

    /** Looks again for the video to preview (after a pause in scrolling, new items, coming back). */
    void refresh() {
        mHandler.removeCallbacks(mCheck);
        mHandler.postDelayed(mCheck, 350);
    }

    void onResume() {
        mPaused = false;
        refresh();
    }

    private void check() {
        if (mPaused || mList == null || !FamilyPreview.isEnabled(mActivity) || mActivity.isFinishing()) {
            return;
        }
        if (mList.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            return;
        }
        if (!canPlay()) {
            stop();
            refresh(); // look again in a moment
            return;
        }
        View target = firstFullyVisibleCard();
        if (target == mCard) {
            return; // same card: keep it (also when its minute of preview is over)
        }
        stop();
        if (target == null) {
            return;
        }
        Video video = (Video) target.getTag(R.id.family_card_thumb_frame);
        if (video == null || video.isLive || video.videoId == null) {
            return;
        }
        mCard = target;
        mVideo = video;
        mPreview.setMuted(sMuted);
        mPreview.startNow(target, video);
        showSoundButton(target, true);
    }

    private View firstFullyVisibleCard() {
        RecyclerView.LayoutManager manager = mList.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) {
            return null;
        }
        LinearLayoutManager layout = (LinearLayoutManager) manager;
        int first = layout.findFirstVisibleItemPosition();
        int last = layout.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) {
            return null;
        }
        for (int i = first; i <= last; i++) {
            View child = layout.findViewByPosition(i);
            if (child != null && child.getTag(R.id.family_card_thumb_frame) instanceof Video && isFullyVisible(child)) {
                return child;
            }
        }
        return null;
    }

    /** The picture of the card is entirely on screen. */
    private boolean isFullyVisible(View card) {
        View frame = card.findViewById(R.id.family_card_thumb_frame);
        if (frame == null || mList == null || !frame.isShown() || frame.getHeight() == 0) {
            return false;
        }
        if (!frame.getGlobalVisibleRect(mRect) || !mList.getGlobalVisibleRect(mListRect)) {
            return false;
        }
        return mRect.height() >= frame.getHeight() && mRect.width() >= frame.getWidth() && mListRect.contains(mRect);
    }

    private void showSoundButton(View card, boolean show) {
        ImageView sound = card.findViewById(R.id.mobile_card_sound);
        if (sound == null) {
            return;
        }
        sound.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            sound.bringToFront();
            sound.setImageResource(sMuted ? R.drawable.mobile_ic_volume_off : R.drawable.mobile_ic_volume_on);
            sound.setOnClickListener(v -> {
                sMuted = !sMuted;
                mPreview.setMuted(sMuted);
                sound.setImageResource(sMuted ? R.drawable.mobile_ic_volume_off : R.drawable.mobile_ic_volume_on);
            });
        }
    }

    /** A card was given another video (list refreshed): its preview no longer fits. */
    void onBound(View card, Video video) {
        if (card == mCard && video != mVideo) {
            stop();
        } else if (card != mCard) {
            showSoundButton(card, false);
        }
        refresh();
    }

    /** Before opening a video: if it's the one being previewed, the player continues from there. */
    void prepareOpen(Video video) {
        mPreview.stopAndContinue(video);
        clearCard();
    }

    void stop() {
        mPreview.stop();
        clearCard();
    }

    private void clearCard() {
        if (mCard != null) {
            showSoundButton(mCard, false);
        }
        mCard = null;
        mVideo = null;
    }

    /** Screen hidden: free the preview player (it starts again when coming back). */
    void release() {
        mPaused = true;
        mHandler.removeCallbacks(mCheck);
        mPreview.release();
        clearCard();
    }
}
