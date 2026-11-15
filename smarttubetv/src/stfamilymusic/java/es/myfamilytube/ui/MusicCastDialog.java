package es.myfamilytube.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;

import java.util.ArrayList;
import java.util.List;

/**
 * «Enviar a…»: the Chromecasts (and TVs or speakers with Chromecast) on the home Wi-Fi.
 * Choosing one starts sending; while sending, it offers to stop.
 */
final class MusicCastDialog {
    private MusicCastDialog() {
    }

    static void show(Activity activity) {
        CastContext cast;
        try {
            cast = CastContext.getSharedInstance(activity.getApplicationContext());
        } catch (Throwable e) {
            Toast.makeText(activity, "Este móvil no puede enviar a un Chromecast (faltan los servicios de Google Play)", Toast.LENGTH_LONG).show();
            return;
        }
        CastSession session = cast.getSessionManager().getCurrentCastSession();
        if (session != null && session.isConnected()) {
            String name = session.getCastDevice() != null ? session.getCastDevice().getFriendlyName() : "el Chromecast";
            new AlertDialog.Builder(activity)
                    .setTitle("Enviando a " + name)
                    .setMessage("La música suena en " + name + ". Los botones de volumen del móvil lo controlan.")
                    .setPositiveButton("Dejar de enviar", (dialog, which) -> cast.getSessionManager().endCurrentSession(true))
                    .setNegativeButton("Cerrar", null)
                    .show();
            return;
        }

        MediaRouter router = MediaRouter.getInstance(activity);
        MediaRouteSelector selector = new MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build();
        List<MediaRouter.RouteInfo> routes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, names);

        int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView empty = new TextView(activity);
        empty.setText("Buscando dispositivos en tu Wi‑Fi…\n\nEl móvil y el Chromecast tienen que estar en la misma red.");
        empty.setPadding(padding, padding / 2, padding, padding / 2);
        empty.setTextSize(15);
        ListView list = new ListView(activity);
        list.setAdapter(adapter);
        box.addView(empty);
        box.addView(list);

        Runnable refresh = () -> {
            routes.clear();
            names.clear();
            for (MediaRouter.RouteInfo route : router.getRoutes()) {
                if (!route.isDefault() && route.isEnabled() && route.matchesSelector(selector)) {
                    routes.add(route);
                    names.add(route.getName());
                }
            }
            adapter.notifyDataSetChanged();
            empty.setVisibility(routes.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        };
        MediaRouter.Callback callback = new MediaRouter.Callback() {
            @Override
            public void onRouteAdded(MediaRouter router, MediaRouter.RouteInfo route) {
                refresh.run();
            }

            @Override
            public void onRouteRemoved(MediaRouter router, MediaRouter.RouteInfo route) {
                refresh.run();
            }

            @Override
            public void onRouteChanged(MediaRouter router, MediaRouter.RouteInfo route) {
                refresh.run();
            }
        };
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
        refresh.run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Enviar a un dispositivo")
                .setView(box)
                .setNegativeButton("Cancelar", null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < routes.size()) {
                router.selectRoute(routes.get(position)); // Google Cast starts sending
                Toast.makeText(activity, "Conectando con " + names.get(position) + "…", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });
        dialog.setOnDismissListener(d -> router.removeCallback(callback));
        dialog.show();
    }
}
