package es.myfamilytube.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;

/**
 * Phone previews: one tap on a thumbnail plays the video inside it (with sound),
 * a second tap on the same thumbnail opens it. Stops when the card scrolls away.
 */
final class MobilePreview {
    private final Activity mActivity;
    private final FamilyPreview mPreview;
    private View mCard;
    private Video mVideo;

    MobilePreview(Activity activity) {
        mActivity = activity;
        mPreview = new FamilyPreview(activity, seconds -> {
            // Previews count towards a kid's daily time
            FamilyProfile profile = FamilyProfiles.instance(activity).getCurrent();
            if (profile.isKid) {
                FamilyProfiles.instance(activity).addUsedSeconds(profile, seconds);
            }
        });
    }

    /** @return true if a preview started; false means "open the video". */
    boolean onThumbnailTapped(View card, Video video) {
        if (!FamilyPreview.isEnabled(mActivity) || video == null || video.isLive || video.videoId == null) {
            return false;
        }
        if (card == mCard && video == mVideo) {
            prepareOpen(video); // second tap: open it, from where the preview is
            return false;
        }
        mPreview.startNow(card, video);
        mCard = card;
        mVideo = video;
        showHintOnce();
        return true;
    }

    /** A card was given another video (list refreshed): its preview no longer fits. */
    void onBound(View card, Video video) {
        if (card == mCard && video != mVideo) {
            stop();
        }
    }

    void onDetached(View card) {
        if (card == mCard) {
            stop();
        }
    }

    /** Before opening a video: if it's the one being previewed, the player continues from there. */
    void prepareOpen(Video video) {
        mPreview.stopAndContinue(video);
        mCard = null;
        mVideo = null;
    }

    void stop() {
        mPreview.stop();
        mCard = null;
        mVideo = null;
    }

    void release() {
        mPreview.release();
        mCard = null;
        mVideo = null;
    }

    private void showHintOnce() {
        SharedPreferences prefs = mActivity.getSharedPreferences("family_ui", Activity.MODE_PRIVATE);
        if (!prefs.getBoolean("preview_hint_shown", false)) {
            prefs.edit().putBoolean("preview_hint_shown", true).apply();
            Toast.makeText(mActivity, "Vista previa. Toca otra vez la miniatura para abrir el vídeo.", Toast.LENGTH_LONG).show();
        }
    }
}
