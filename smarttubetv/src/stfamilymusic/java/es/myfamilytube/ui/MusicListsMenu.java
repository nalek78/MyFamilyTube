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
import es.myfamilytube.auto.L;
import es.myfamilytube.auto.LocalLists;
import es.myfamilytube.profiles.FamilyAccounts;

import io.reactivex.Observable;

/**
 * The song lists: on the phone (no account needed) and, with a Google account, also the
 * account's (they are also in YouTube and on the TV). Add a song to lists, create a list, rename
 * it, delete it, take a song out of it. Lists on the phone have ids starting with «ll:».
 */
final class MusicListsMenu {
    static final String LOCAL = "ll:";

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

    private static boolean hasAccount() {
        return !TextUtils.isEmpty(FamilyAccounts.currentKey());
    }

    static boolean isLocal(String listId) {
        return listId != null && listId.startsWith(LOCAL);
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

    private static final class Choice {
        final String id; // «ll:…» on the phone, else the YouTube list
        final String name;
        final boolean before;

        Choice(String id, String name, boolean before) {
            this.id = id;
            this.name = name;
            this.before = before;
        }
    }

    /** The lists, ticked where the song already is; also «Nueva lista». */
    void addSong(String videoId, CharSequence songTitle, CharSequence artist) {
        if (TextUtils.isEmpty(videoId)) {
            return;
        }
        String title = songTitle != null ? songTitle.toString() : "";
        String author = artist != null ? artist.toString() : "";
        int dot = author.indexOf(" · ");
        if (dot > 0) {
            author = author.substring(0, dot);
        }
        String songArtist = author;
        List<Choice> choices = new ArrayList<>();
        for (LocalLists.Entry entry : LocalLists.all(mActivity)) {
            choices.add(new Choice(LOCAL + entry.id, entry.name, LocalLists.contains(mActivity, entry.id, videoId)));
        }
        if (!hasAccount()) {
            showLists(videoId, title, songArtist, choices, false);
            return;
        }
        Toast.makeText(mActivity, L.t("Cargando tus listas…"), Toast.LENGTH_SHORT).show();
        items().getPlaylistsInfoObserve(videoId).subscribe(
                infos -> mHandler.post(() -> {
                    if (infos != null) {
                        for (PlaylistInfo info : infos) {
                            if (info != null && info.getPlaylistId() != null) {
                                choices.add(new Choice(info.getPlaylistId(), info.getTitle(), info.isSelected()));
                            }
                        }
                    }
                    showLists(videoId, title, songArtist, choices, true);
                }),
                error -> mHandler.post(() -> {
                    toast(L.t("No se han podido cargar las listas de tu cuenta; salen solo las del móvil."));
                    showLists(videoId, title, songArtist, choices, true);
                }));
    }

    private void showLists(String videoId, String title, String artist, List<Choice> choices, boolean withAccount) {
        if (mActivity.isFinishing()) {
            return;
        }
        String[] names = new String[choices.size()];
        boolean[] after = new boolean[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            Choice choice = choices.get(i);
            names[i] = withAccount ? choice.name + (isLocal(choice.id) ? L.t("  (en el móvil)") : L.t("  (en YouTube)")) : choice.name;
            after[i] = choice.before;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(mActivity)
                .setTitle(!title.isEmpty() ? L.t("Añadir «") + title + "»" : L.t("Añadir a una lista"))
                .setNeutralButton(L.t("Nueva lista"), (d, w) -> newList(videoId, title, artist))
                .setNegativeButton(L.t("Cancelar"), null);
        if (choices.isEmpty()) {
            dialog.setMessage(L.t("Todavía no tienes listas. Crea una con «Nueva lista»."));
        } else {
            dialog.setMultiChoiceItems(names, after, (d, which, checked) -> after[which] = checked)
                    .setPositiveButton(L.t("Guardar"), (d, w) -> {
                        boolean local = false;
                        for (int i = 0; i < choices.size(); i++) {
                            Choice choice = choices.get(i);
                            if (after[i] == choice.before) {
                                continue;
                            }
                            String name = choice.name;
                            if (isLocal(choice.id)) {
                                String id = choice.id.substring(LOCAL.length());
                                if (after[i]) {
                                    LocalLists.add(mActivity, id, videoId, title, artist, null);
                                } else {
                                    LocalLists.remove(mActivity, id, videoId);
                                }
                                toast((after[i] ? L.t("Añadida a «") : L.t("Quitada de «")) + name + "»");
                                local = true;
                            } else if (after[i]) {
                                run(items().addToPlaylistObserve(choice.id, videoId), L.t("No se ha podido añadir a «") + name + "»",
                                        () -> toast(L.t("Añadida a «") + name + "»"));
                            } else {
                                run(items().removeFromPlaylistObserve(choice.id, videoId), L.t("No se ha podido quitar de «") + name + "»",
                                        () -> toast(L.t("Quitada de «") + name + "»"));
                            }
                        }
                        if (local) {
                            changed();
                        }
                    });
        }
        dialog.show();
    }

    /** A new list (with the song in it, if there is one). On the phone, or on the account if there is one and you choose so. */
    void newList(String videoId) {
        newList(videoId, null, null);
    }

    private void newList(String videoId, String title, String artist) {
        askName(L.t("Nueva lista"), "", name -> {
            if (!hasAccount()) {
                createLocal(name, videoId, title, artist);
                return;
            }
            new AlertDialog.Builder(mActivity)
                    .setTitle(L.t("¿Dónde guardo «") + name + "»?")
                    .setItems(new String[] {L.t("En el móvil (sin cuenta)"), L.t("En tu cuenta de YouTube (también en la tele)")}, (d, which) -> {
                        if (which == 0) {
                            createLocal(name, videoId, title, artist);
                        } else {
                            createOnAccount(name, videoId);
                        }
                    })
                    .show();
        });
    }

    private void createLocal(String name, String videoId, String title, String artist) {
        String id = LocalLists.create(mActivity, name);
        if (id == null) {
            toast(L.t("No se ha podido crear la lista"));
            return;
        }
        if (!TextUtils.isEmpty(videoId)) {
            LocalLists.add(mActivity, id, videoId, title, artist, null);
        }
        toast(L.t("Lista «") + name + L.t("» creada en el móvil"));
        changed();
    }

    private void createOnAccount(String name, String videoId) {
        if (TextUtils.isEmpty(videoId)) {
            toast(L.t("Las listas de YouTube se crean con una canción: mantén pulsada una canción → «Añadir a una lista…»."));
            return;
        }
        if (mController != null) {
            Bundle extras = new Bundle();
            extras.putString(CarMusicService.EXTRA_NAME, name);
            mController.getTransportControls().sendCustomAction(CarMusicService.ACTION_LIST_CREATED, extras); // it is music
        }
        run(items().createPlaylistObserve(name, videoId), L.t("No se ha podido crear la lista"),
                () -> toast(L.t("Lista «") + name + L.t("» creada. Puede tardar un poco en salir en «Tus listas».")));
    }

    /** Takes the song out of the list open now. */
    void removeSong(String listId, String videoId, CharSequence songTitle, CharSequence listTitle) {
        new AlertDialog.Builder(mActivity)
                .setTitle(L.t("Quitar de la lista"))
                .setMessage(L.t("¿Quitar «") + songTitle + L.t("» de «") + listTitle + "»?")
                .setPositiveButton(L.t("Quitar"), (d, w) -> {
                    if (isLocal(listId)) {
                        LocalLists.remove(mActivity, listId.substring(LOCAL.length()), videoId);
                        toast(L.t("Quitada de la lista"));
                        changed();
                    } else {
                        run(items().removeFromPlaylistObserve(listId, videoId),
                                L.t("No se ha podido quitar (¿es una lista de otra persona?)"), () -> toast(L.t("Quitada de la lista")));
                    }
                })
                .setNegativeButton(L.t("Cancelar"), null)
                .show();
    }

    // ---------------------------------------------------------------- a list

    void rename(String listId, String oldName) {
        askName(L.t("Cambiar nombre"), oldName, name -> {
            if (isLocal(listId)) {
                LocalLists.rename(mActivity, listId.substring(LOCAL.length()), name);
                toast(L.t("Ahora se llama «") + name + "»");
                changed();
                return;
            }
            if (mController != null) {
                Bundle extras = new Bundle();
                extras.putString(CarMusicService.EXTRA_NAME, name);
                mController.getTransportControls().sendCustomAction(CarMusicService.ACTION_LIST_CREATED, extras);
            }
            run(items().renamePlaylistObserve(listId, name), L.t("No se ha podido cambiar el nombre (¿es una lista de otra persona?)"),
                    () -> toast(L.t("Ahora se llama «") + name + "»"));
        });
    }

    void delete(String listId, String name, Runnable done) {
        boolean local = isLocal(listId);
        new AlertDialog.Builder(mActivity)
                .setTitle(L.t("Borrar «") + name + "»")
                .setMessage(local ? L.t("Se borrará la lista del móvil. No se puede deshacer.")
                        : L.t("Se borrará de tu cuenta de YouTube (también en la tele). Las canciones no se borran."))
                .setPositiveButton(L.t("Borrar"), (d, w) -> {
                    if (local) {
                        LocalLists.delete(mActivity, listId.substring(LOCAL.length()));
                        toast(L.t("Lista borrada"));
                        changed();
                        done.run();
                        return;
                    }
                    run(items().removePlaylistObserve(listId), L.t("No se ha podido borrar (¿es una lista de otra persona?)"), () -> {
                        toast(L.t("Lista borrada"));
                        done.run();
                    });
                })
                .setNegativeButton(L.t("Cancelar"), null)
                .show();
    }

    private interface NameDone {
        void onName(String name);
    }

    private void askName(String title, String current, NameDone done) {
        EditText field = new EditText(mActivity);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setSingleLine(true);
        field.setHint(L.t("Nombre de la lista"));
        field.setText(current);
        field.setSelection(field.getText().length());
        FrameLayout box = new FrameLayout(mActivity);
        int padding = (int) (20 * mActivity.getResources().getDisplayMetrics().density);
        box.setPadding(padding, padding / 2, padding, 0);
        box.addView(field);
        new AlertDialog.Builder(mActivity)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(L.t("Aceptar"), (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (!name.isEmpty()) {
                        done.onName(name);
                    }
                })
                .setNegativeButton(L.t("Cancelar"), null)
                .show();
    }
}
