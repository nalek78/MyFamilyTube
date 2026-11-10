package es.myfamilytube.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** Phone only: how to make MyFamilyTube the app that opens YouTube links. */
public final class FamilyLinks {
    private FamilyLinks() {
    }

    public static void showSetup(Activity activity) {
        String steps = Build.VERSION.SDK_INT >= 31
                ? "1. En los ajustes de la app YouTube, entra en «Abrir de forma predeterminada» y desactiva «Abrir enlaces compatibles».\n\n"
                  + "2. Pulsa «Continuar»: en «Abrir de forma predeterminada» de MyFamilyTube activa «Abrir enlaces compatibles», "
                  + "toca «Añadir enlace» y marca youtube.com, www.youtube.com, m.youtube.com y youtu.be."
                : "Pulsa «Continuar» y, en «Abrir de forma predeterminada», elige abrir los enlaces compatibles con esta app.\n\n"
                  + "Si al tocar un enlace Android pregunta con qué app abrirlo, elige MyFamilyTube y «Siempre».";
        new AlertDialog.Builder(activity)
                .setTitle("Abrir enlaces de YouTube aquí")
                .setMessage(steps)
                .setPositiveButton("Continuar", (dialog, which) -> openSettings(activity, activity.getPackageName()))
                .setNeutralButton("Ajustes de YouTube", (dialog, which) -> openSettings(activity, "com.google.android.youtube"))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private static void openSettings(Activity activity, String packageName) {
        Uri uri = Uri.parse("package:" + packageName);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, uri));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // fall back to the app's details
            }
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri));
        } catch (ActivityNotFoundException e) {
            // nothing else to open
        }
    }
}
