package es.myfamilytube.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.TextUtils;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.ArrayList;
import java.util.List;

import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;

/** Long press on a video: same options as on the TV. */
final class MobileVideoMenu {
    private MobileVideoMenu() {
    }

    static void show(Activity activity, Video video, Runnable engineMenu) {
        FamilyProfiles profiles = FamilyProfiles.instance(activity);
        if (profiles.getCurrent().isKid) {
            return; // no menus for kids
        }
        List<FamilyProfile> kids = new ArrayList<>();
        for (FamilyProfile profile : profiles.getAll()) {
            if (profile.isKid) {
                kids.add(profile);
            }
        }
        String channelName = !TextUtils.isEmpty(video.getAuthor()) ? video.getAuthor() : "este canal";
        if (kids.isEmpty() || TextUtils.isEmpty(video.channelId)) {
            engineMenu.run();
            return;
        }
        List<String> options = new ArrayList<>();
        for (FamilyProfile kid : kids) {
            options.add(kid.isChannelApproved(video.channelId)
                    ? "Quitar «" + channelName + "» de " + kid.name
                    : "Aprobar «" + channelName + "» para " + kid.name);
        }
        options.add("Más opciones del vídeo");
        new AlertDialog.Builder(activity)
                .setTitle(channelName)
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    if (which >= kids.size()) {
                        engineMenu.run();
                        return;
                    }
                    FamilyProfile kid = kids.get(which);
                    if (kid.isChannelApproved(video.channelId)) {
                        kid.approvedChannels.remove(video.channelId);
                        Toast.makeText(activity, "Quitado de " + kid.name, Toast.LENGTH_SHORT).show();
                    } else {
                        kid.approvedChannels.put(video.channelId, channelName);
                        Toast.makeText(activity, "Aprobado para " + kid.name, Toast.LENGTH_SHORT).show();
                    }
                    profiles.update(kid);
                })
                .show();
    }
}
