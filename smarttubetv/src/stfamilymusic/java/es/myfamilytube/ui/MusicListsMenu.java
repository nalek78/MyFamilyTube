package es.myfamilytube.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.MediaControllerCompat;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.List;

import es.myfamilytube.auto.CarMusicService;
import es.myfamilytube.profiles.FamilyAccounts;

import io.reactivex.Observable;

/**
 * The profile's song lists, on its YouTube account (so they are also in YouTube and on the TV):
 * add a song to lists, create a list, rename it, delete it, take a song out of it.
 */
final class MusicListsMenu {
    private final Activity mActivity;
    private final MediaControllerCompat mController;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    MusicListsMenu(Activity activity, MediaControllerCompat controller) {
        mActivity = activity;
        mController = controller;
    }

    private MediaItemService items() {
        return YouTubeServiceManager.instance().getMediaItemService();
    }

    private boolean hasAccount() {
        if (TextUtils.isEmpty(FamilyAccounts.currentKey())) {
            toast("Para tener listas, este perfil necesita una cuenta de Google (⋮ → Cambiar perfil).");
            return false;
        }
        return true;
    }

    private void toast(String text) {
        if (!mActivity.isFinishing()) {
            Toast.makeText(mActivity, text, Toast.LENGTH_LONG).show();
        }
    }

    /** Tell the music service: the lists changed (it loads them again, also in the car). */
    private void changed() {
        if (mController != null) {
            mController.getTransportControls().sendCustomAction(CarMusicService.ACTION_LISTS_CHANGED, null);
        }
    }

    /** Runs a YouTube change; done (on the screen) when it worked. */
    private void run(Observable<Void> action, String failed, Runnable done) {
        action.subscribe(ignored -> {
        }, error -> mHandler.post(() -> toast(failed)), () -> mHandler.post(() -> {
            done.run();
            changed();
        }));
    }

    // ---------------------------------------------------------------- a song

    /** The profile's lists, ticked where the song already is; also «Nueva lista». */
    void addSong(String videoId, CharSequence songTitle) {
        if (TextUtils.isEmpty(videoId) || !hasAccount()) {
            return;
        }
        Toast.makeText(mActivity, "Cargando tus listas…", Toast.LENGTH_SHORT).show();
        items().getPlaylistsInfoObserve(videoId).subscribe(
                infos -> mHandler.post(() -> showLists(videoId, songTitle, infos)),
                error -> mHandler.post(() -> toast("No se han podido cargar tus listas. Comprueba la conexión.")));
    }

    private void showLists(String videoId, CharSequence songTitle, List<PlaylistInfo> infos) {
        if (mActivity.isFinishing()) {
            return;
        }
        List<PlaylistInfo> lists = new ArrayList<>();
        if (infos != null) {
            for (PlaylistInfo info : infos) {
                if (info != null && info.getPlaylistId() != null) {
                    lists.add(info);
                }
            }
        }
        String[] names = new String[lists.size()];
        boolean[] before = new boolean[lists.size()];
        boolean[] after = new boolean[lists.size()];
        for (int i = 0; i < lists.size(); i++) {
            names[i] = lists.get(i).getTitle();
            before[i] = lists.get(i).isSelected();
            after[i] = before[i];
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(mActivity)
                .setTitle(songTitle != null ? "Añadir «" + songTitle + "»" : "Añadir a una lista")
                .setNeutralButton("Nueva lista", (d, w) -> newList(videoId))
                .setNegativeButton("Cancelar", null);
        if (lists.isEmpty()) {
            dialog.setMessage("Todavía no tienes listas. Crea una con «Nueva lista».");
        } else {
            dialog.setMultiChoiceItems(names, after, (d, which, checked) -> after[which] = checked)
                    .setPositiveButton("Guardar", (d, w) -> {
                        for (int i = 0; i < lists.size(); i++) {
                            if (after[i] == before[i]) {
                                continue;
                            }
                            String name = names[i];
                            String id = lists.get(i).getPlaylistId();
                            if (after[i]) {
                                run(items().addToPlaylistObserve(id, videoId), "No se ha podido añadir a «" + name + "»",
                                        () -> toast("Añadida a «" + name + "»"));
                            } else {
                                run(items().removeFromPlaylistObserve(id, videoId), "No se ha podido quitar de «" + name + "»",
                                        () -> toast("Quitada de «" + name + "»"));
                            }
                        }
                    });
        }
        dialog.show();
    }

    /** A new list with the song in it. */
    void newList(String videoId) {
        askName("Nueva lista", "", name -> {
            if (mController != null) {
                Bundle extras = new Bundle();
                extras.putString(CarMusicService.EXTRA_NAME, name);
                mController.getTransportControls().sendCustomAction(CarMusicService.ACTION_LIST_CREATED, extras); // it is music
            }
            run(items().createPlaylistObserve(name, videoId), "No se ha podido crear la lista",
                    () -> toast("Lista «" + name + "» creada. Puede tardar un poco en salir en «Tus listas»."));
        });
    }

    /** Takes the song out of the list open now. */
    void removeSong(String playlistId, String videoId, CharSequence songTitle, CharSequence listTitle) {
        if (!hasAccount()) {
            return;
        }
        new AlertDialog.Builder(mActivity)
                .setTitle("Quitar de la lista")
                .setMessage("¿Quitar «" + songTitle + "» de «" + listTitle + "»?")
                .setPositiveButton("Quitar", (d, w) -> run(items().removeFromPlaylistObserve(playlistId, videoId),
                        "No se ha podido quitar (¿es una lista de otra persona?)", () -> toast("Quitada de la lista")))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------------------------------------------------------------- a list

    void rename(String playlistId, String oldName) {
        if (!hasAccount()) {
            return;
        }
        askName("Cambiar nombre", oldName, name -> {
            if (mController != null) {
                Bundle extras = new Bundle();
                extras.putString(CarMusicService.EXTRA_NAME, name);
                mController.getTransportControls().sendCustomAction(CarMusicService.ACTION_LIST_CREATED, extras);
            }
            run(items().renamePlaylistObserve(playlistId, name), "No se ha podido cambiar el nombre (¿es una lista de otra persona?)",
                    () -> toast("Ahora se llama «" + name + "»"));
        });
    }

    void delete(String playlistId, String name, Runnable done) {
        if (!hasAccount()) {
            return;
        }
        new AlertDialog.Builder(mActivity)
                .setTitle("Borrar «" + name + "»")
                .setMessage("Se borrará de tu cuenta de YouTube (también en la tele). Las canciones no se borran.")
                .setPositiveButton("Borrar", (d, w) -> run(items().removePlaylistObserve(playlistId),
                        "No se ha podido borrar (¿es una lista de otra persona?)", () -> {
                            toast("Lista borrada");
                            done.run();
                        }))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private interface NameDone {
        void onName(String name);
    }

    private void askName(String title, String current, NameDone done) {
        EditText field = new EditText(mActivity);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setSingleLine(true);
        field.setHint("Nombre de la lista");
        field.setText(current);
        field.setSelection(field.getText().length());
        FrameLayout box = new FrameLayout(mActivity);
        int padding = (int) (20 * mActivity.getResources().getDisplayMetrics().density);
        box.setPadding(padding, padding / 2, padding, 0);
        box.addView(field);
        new AlertDialog.Builder(mActivity)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Aceptar", (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (!name.isEmpty()) {
                        done.onName(name);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
