package es.myfamilytube.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

import es.myfamilytube.profiles.FamilyProfile;

/** Draws a profile's avatar (picture or coloured initial) with the red focus ring. */
final class FamilyAvatars {
    static final int RING_COLOR = 0xFFFF3D33;
    /** Same order as {@link FamilyProfile#AVATARS}. */
    private static final int[] PICTURES = {
            R.drawable.family_avatar_cat, R.drawable.family_avatar_dog, R.drawable.family_avatar_bear,
            R.drawable.family_avatar_rabbit, R.drawable.family_avatar_panda, R.drawable.family_avatar_fox,
            R.drawable.family_avatar_owl, R.drawable.family_avatar_robot
    };

    private FamilyAvatars() {
    }

    static int pictureRes(String avatar) {
        for (int i = 0; i < FamilyProfile.AVATARS.length && i < PICTURES.length; i++) {
            if (FamilyProfile.AVATARS[i].equals(avatar)) {
                return PICTURES[i];
            }
        }
        return 0;
    }

    /** @param ringDp ring width when focused (0 = no ring) */
    static void apply(TextView view, String avatar, int color, String initial, boolean focused, int ringDp) {
        Context context = view.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int ring = (int) (ringDp * density);
        int picture = pictureRes(avatar);

        if (picture != 0) {
            view.setText(null);
            Drawable image = ContextCompat.getDrawable(context, picture);
            if (focused && ring > 0) {
                GradientDrawable stroke = new GradientDrawable();
                stroke.setShape(GradientDrawable.OVAL);
                stroke.setColor(0x00000000);
                stroke.setStroke(ring, RING_COLOR);
                view.setBackground(new LayerDrawable(new Drawable[]{image, stroke}));
            } else {
                view.setBackground(image);
            }
            return;
        }

        view.setText(initial);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        if (focused && ring > 0) {
            circle.setStroke(ring, RING_COLOR);
        }
        view.setBackground(circle);
    }

    static void apply(TextView view, FamilyProfile profile, boolean focused, int ringDp) {
        apply(view, profile.avatar, profile.color, profile.getInitial(), focused, ringDp);
    }
}
