package es.myfamilytube.profiles;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.ContentGate;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Parental control for kids profiles: what may appear, what may play, daily time and player buttons.
 * Adults are never filtered (apart from Shorts, which the UI hides for everyone).
 */
public final class FamilyRules implements ContentGate.Rules {
    /** Blocked for every kids profile (accents removed, lower case). */
    private static final String[] BLOCKED_ALWAYS = {
            "porno", "porn", "xxx", "sexo", "sexy", "sexual", "desnud", "nude", "naked", "erotic", "onlyfans",
            "hentai", "nsfw", "+18", "18+", "stripper", "striptease", "fetich", "fetish", "lenceria", "lingerie",
            "twerk", "milf", "escort", "prostitu", "webcam girl", "hot girl", "chicas hot", "sugar daddy",
            "droga", "cocaina", "cocaine", "marihuana", "heroina", "heroin", "meth", "crack",
            "suicid", "autolesion", "self harm", "selfharm", "cutting",
            "gore", "decapit", "tortura", "torture", "masacre", "massacre", "snuff", "violacion", "rape",
            "asesinato real", "real murder", "muertes reales", "real death", "accidentes graves", "brutal",
            "apuesta", "apuestas", "casino", "gambling", "betting"
    };
    /** Also blocked up to 8 years old. */
    private static final String[] BLOCKED_YOUNG = {
            "terror", "horror", "miedo", "scary", "creepy", "creepypasta", "jumpscare", "susto",
            "zombi", "zombie", "asesino", "killer", "sangre", "blood", "muerte", "death", "matar", "kill",
            "pelea", "fight", "arma", "gun", "disparo", "shooting", "guerra", "war", "demonio", "demon",
            "exorcis", "posesion", "paranormal", "fantasmas reales", "ouija", "bromas pesadas", "prank"
    };
    /** Player buttons hidden while a kid with "hide comments" is watching. */
    private static final int KID_HIDDEN_BUTTONS = PlayerTweaksData.PLAYER_BUTTON_CHAT | PlayerTweaksData.PLAYER_BUTTON_VIDEO_INFO;
    private static final String PREFS = "family_rules";
    private static final String KEY_SAVED_BUTTONS = "saved_buttons";
    private static final long TICK_MS = 15_000;

    private final Context mContext;
    private final FamilyProfiles mProfiles;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Activity mPlayer;
    private long mLastTickMs;

    private FamilyRules(Context context) {
        mContext = context.getApplicationContext();
        mProfiles = FamilyProfiles.instance(mContext);
    }

    /** Installs the rules for the whole app. Call once from the Application. */
    public static void install(Application app) {
        FamilyRules rules = new FamilyRules(app);
        ContentGate.setRules(rules);
        rules.mProfiles.addListener(rules::applyPlayerButtons);
        rules.applyPlayerButtons();
        app.registerActivityLifecycleCallbacks(rules.mPlaybackWatcher);
    }

    private FamilyProfile kid() {
        FamilyProfile current = mProfiles.getCurrent();
        return current.isKid ? current : null;
    }

    // ---------------------------------------------------------------- lists and playback

    @Override
    public boolean allows(Video video) {
        // MyFamilyTube has no Shorts, for anyone and on every screen (channels, search, suggestions…)
        if (video.isShorts || normalize(video.getTitle()).contains("#shorts")) {
            return false;
        }
        FamilyProfile kid = kid();
        if (kid == null) {
            return true;
        }
        if (kid.approvedOnly) {
            return kid.isChannelApproved(video.channelId);
        }
        return !hasBlockedWords(kid, video);
    }

    @Override
    public boolean allowsPlayback(Context context, Video video) {
        FamilyProfile kid = kid();
        if (kid == null) {
            return true;
        }
        Context target = context != null ? context : mContext;
        if (mProfiles.isTimeUp(kid)) {
            Toast.makeText(target, "Se ha acabado el tiempo de hoy. Pide a un adulto que lo amplíe.", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!allows(video)) {
            Toast.makeText(target, kid.approvedOnly
                    ? "Este vídeo no es de un canal aprobado."
                    : "Este vídeo no está disponible en este perfil.", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private static String normalize(CharSequence text) {
        if (text == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(text.toString(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT);
    }

    private static boolean hasBlockedWords(FamilyProfile kid, Video video) {
        String text = normalize(video.getTitle()) + " " + normalize(video.getAuthor()) + " " + normalize(video.description);
        if (containsAny(text, BLOCKED_ALWAYS)) {
            return true;
        }
        return kid.ageGroup <= FamilyProfile.AGE_5_TO_8 && containsAny(text, BLOCKED_YOUNG);
    }

    private static boolean containsAny(String text, String[] words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- player buttons

    /** Hides comments and chat for kids; gives adults back exactly what they had. */
    private void applyPlayerButtons() {
        PlayerTweaksData tweaks = PlayerTweaksData.instance(mContext);
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        FamilyProfile kid = kid();
        boolean saved = prefs.contains(KEY_SAVED_BUTTONS);

        if (kid != null && kid.hideComments) {
            if (!saved) {
                int enabled = 0;
                if (tweaks.isPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_CHAT)) {
                    enabled |= PlayerTweaksData.PLAYER_BUTTON_CHAT;
                }
                if (tweaks.isPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_VIDEO_INFO)) {
                    enabled |= PlayerTweaksData.PLAYER_BUTTON_VIDEO_INFO;
                }
                prefs.edit().putInt(KEY_SAVED_BUTTONS, enabled).apply();
            }
            tweaks.setPlayerButtonDisabled(KID_HIDDEN_BUTTONS);
        } else if (saved) {
            int enabled = prefs.getInt(KEY_SAVED_BUTTONS, 0);
            if (enabled != 0) {
                tweaks.setPlayerButtonEnabled(enabled);
            }
            prefs.edit().remove(KEY_SAVED_BUTTONS).apply();
        }
    }

    // ---------------------------------------------------------------- daily time

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            Activity player = mPlayer;
            FamilyProfile kid = kid();
            if (player == null || kid == null) {
                return;
            }
            long now = System.currentTimeMillis();
            int seconds = (int) Math.min(60, Math.max(0, (now - mLastTickMs) / 1000));
            mLastTickMs = now;
            mProfiles.addUsedSeconds(kid, seconds);

            if (mProfiles.isTimeUp(kid)) {
                Toast.makeText(player, "Se ha acabado el tiempo de hoy.", Toast.LENGTH_LONG).show();
                player.finish();
                return;
            }
            if (mProfiles.getMinutesLeft(kid) == 5 && seconds > 0 && (mProfiles.getUsedSecondsToday(kid) % 60) < TICK_MS / 1000) {
                Toast.makeText(player, "Quedan 5 minutos.", Toast.LENGTH_LONG).show();
            }
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    private static boolean isPlayer(Activity activity) {
        return activity.getClass().getSimpleName().equals("PlaybackActivity");
    }

    private final Application.ActivityLifecycleCallbacks mPlaybackWatcher = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityResumed(Activity activity) {
            if (isPlayer(activity)) {
                mPlayer = activity;
                mLastTickMs = System.currentTimeMillis();
                mHandler.removeCallbacks(mTick);
                mHandler.postDelayed(mTick, TICK_MS);
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (activity == mPlayer) {
                mHandler.removeCallbacks(mTick);
                mTick.run(); // count the last seconds
                mHandler.removeCallbacks(mTick);
                mPlayer = null;
            }
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    };
}
