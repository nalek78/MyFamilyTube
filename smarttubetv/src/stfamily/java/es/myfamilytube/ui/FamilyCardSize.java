package es.myfamilytube.ui;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Size of the video thumbnails, chosen in Ajustes › Pantalla.
 * The base size comes from family_card_w / family_card_h; this scales it.
 */
final class FamilyCardSize {
    private static final String PREFS = "family_ui";
    private static final String KEY_PERCENT = "card_size_percent";

    static final int[] PERCENTS = {100, 115, 130};
    static final String[] LABELS = {"Normal", "Grande", "Muy grande"};
    private static final int DEFAULT_PERCENT = 115;

    /** Space a row needs under the thumbnail: title (2 lines), subtitle and margins. */
    private static final int TEXT_BLOCK_DP = 73;

    private FamilyCardSize() {
    }

    static int percent(Context context) {
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PERCENT, DEFAULT_PERCENT);
        for (int allowed : PERCENTS) {
            if (allowed == value) {
                return value;
            }
        }
        return DEFAULT_PERCENT;
    }

    static void setPercent(Context context, int percent) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PERCENT, percent).apply();
    }

    static int index(Context context) {
        int current = percent(context);
        for (int i = 0; i < PERCENTS.length; i++) {
            if (PERCENTS[i] == current) {
                return i;
            }
        }
        return 1;
    }

    static int cardWidth(Context context) {
        return scaled(context, R.dimen.family_card_w);
    }

    static int thumbHeight(Context context) {
        return scaled(context, R.dimen.family_card_h);
    }

    /** Height of a row's grid: thumbnail + texts + room for the focus zoom. */
    static int rowHeight(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        return thumbHeight(context) + Math.round(TEXT_BLOCK_DP * density);
    }

    private static int scaled(Context context, int dimenId) {
        return Math.round(context.getResources().getDimensionPixelSize(dimenId) * percent(context) / 100f);
    }
}
