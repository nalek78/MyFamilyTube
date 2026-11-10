package es.myfamilytube.ui;

import android.content.Context;

/** Phone only: what a playing video does when you leave the app or turn the screen off. */
public final class FamilyBackground {
    public static final int FLOATING = 0; // floating window, and sound with the screen off
    public static final int SOUND = 1;    // only the sound keeps playing
    public static final int STOP = 2;     // stops, like on the TV
    public static final String[] LABELS = {"Ventana flotante", "Solo sonido", "Parar"};

    private static final String PREFS = "family_ui";
    private static final String KEY = "background_mode";

    private FamilyBackground() {
    }

    public static int get(Context context) {
        int mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, FLOATING);
        return mode >= FLOATING && mode <= STOP ? mode : FLOATING;
    }

    public static void set(Context context, int mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply();
    }
}
