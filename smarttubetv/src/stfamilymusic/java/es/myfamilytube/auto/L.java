package es.myfamilytube.auto;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The app's language. The texts are written in Spanish in the code (the default language); for
 * the others, assets/i18n/<code>.json says what each Spanish text becomes. A text not in the
 * file stays in Spanish.
 */
public final class L {
    public static final String[] CODES = {"es", "en", "fr", "de", "it"};
    public static final String[] NAMES = {"Español", "English", "Français", "Deutsch", "Italiano"};

    private static final String PREFS = "music_language";
    private static volatile String sLang = "es";
    private static volatile Map<String, String> sTexts = new HashMap<>();
    private static boolean sLoaded;

    private L() {
    }

    /** Loads the chosen language (once; cheap afterwards). */
    public static synchronized void init(Context context) {
        if (sLoaded) {
            return;
        }
        sLoaded = true;
        load(context.getApplicationContext(), prefs(context).getString("lang", "es"));
        es.myfamilytube.FamilyTexts.setTranslator(new es.myfamilytube.FamilyTexts.Translator() {
            @Override
            public String text(String spanish) {
                return t(spanish);
            }

            @Override
            public void views(View root) {
                translate(root);
            }
        });
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String lang() {
        return sLang;
    }

    public static synchronized void setLang(Context context, String code) {
        prefs(context).edit().putString("lang", code).apply();
        load(context.getApplicationContext(), code);
        sLoaded = true;
    }

    private static void load(Context context, String code) {
        Map<String, String> texts = new HashMap<>();
        if (!"es".equals(code)) {
            try (InputStream in = context.getAssets().open("i18n/" + code + ".json")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                JSONObject json = new JSONObject(out.toString("UTF-8"));
                for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    texts.put(key, json.optString(key, key));
                }
            } catch (Exception e) {
                code = "es"; // no file: Spanish
            }
        }
        sTexts = texts;
        sLang = code;
    }

    /** The text in the chosen language. */
    public static String t(String spanish) {
        if (spanish == null) {
            return null;
        }
        String text = sTexts.get(spanish);
        return text != null ? text : spanish;
    }

    /** The screen's own texts (the layouts are written in Spanish). */
    public static void translate(View view) {
        if (sTexts.isEmpty() || view == null) {
            return;
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getText() != null && text.getText().length() > 0) {
                String translated = sTexts.get(text.getText().toString());
                if (translated != null) {
                    text.setText(translated);
                }
            }
            if (text.getHint() != null) {
                String translated = sTexts.get(text.getHint().toString());
                if (translated != null) {
                    text.setHint(translated);
                }
            }
        }
        if (view.getContentDescription() != null) {
            String translated = sTexts.get(view.getContentDescription().toString());
            if (translated != null) {
                view.setContentDescription(translated);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                translate(group.getChildAt(i));
            }
        }
    }
}
