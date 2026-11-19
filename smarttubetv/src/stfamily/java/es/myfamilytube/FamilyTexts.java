package es.myfamilytube;

import android.view.View;

/**
 * Shared screens (update notice, Google sign-in) are written in Spanish. An app with other
 * languages (My Family Music) sets the translator; the others leave them as they are.
 */
public final class FamilyTexts {
    public interface Translator {
        String text(String spanish);

        void views(View root);
    }

    private static volatile Translator sTranslator;

    private FamilyTexts() {
    }

    public static void setTranslator(Translator translator) {
        sTranslator = translator;
    }

    public static String t(String spanish) {
        Translator translator = sTranslator;
        return translator != null ? translator.text(spanish) : spanish;
    }

    public static void translate(View root) {
        Translator translator = sTranslator;
        if (translator != null) {
            translator.views(root);
        }
    }
}
