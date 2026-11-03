package es.myfamilytube.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import es.myfamilytube.FamilyDiagnostics;

/** Shows the diagnostics log so it can be photographed and sent. */
final class FamilyDiagnosticsDialog {
    private static final int LINES = 45;

    private FamilyDiagnosticsDialog() {
    }

    static void show(Activity activity) {
        FamilyDiagnostics diagnostics = FamilyDiagnostics.instance(activity);
        StringBuilder text = new StringBuilder(diagnostics.deviceSummary()).append("\n\n");
        List<String> lines = diagnostics.lastLines(LINES);
        if (lines.isEmpty()) {
            text.append("Todavía no hay nada registrado.");
        }
        for (String line : lines) {
            text.append(line).append('\n');
        }

        float density = activity.getResources().getDisplayMetrics().density;
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(11);
        view.setTextColor(0xFFF2F2F2);
        view.setTextIsSelectable(false);
        int pad = (int) (16 * density);
        view.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(0xFF101114);
        scroll.setFocusable(true);
        scroll.addView(view);
        // Scroll to the newest lines
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));

        new AlertDialog.Builder(activity)
                .setTitle("Diagnóstico · haz una foto y envíala")
                .setView(scroll)
                .setPositiveButton("Cerrar", null)
                .setNeutralButton("Borrar registro", (dialog, which) -> diagnostics.clear())
                .show();
    }

    /** After the app closed unexpectedly: say why and offer the details. */
    static void showProblem(Activity activity, String reason) {
        new AlertDialog.Builder(activity)
                .setTitle("La app se cerró sola")
                .setMessage("Motivo: " + reason + ".\n\nPuedes ver los detalles ahora o más tarde en Ajustes › Diagnóstico.")
                .setPositiveButton("Ver detalles", (dialog, which) -> show(activity))
                .setNegativeButton("Ahora no", null)
                .show();
    }
}
