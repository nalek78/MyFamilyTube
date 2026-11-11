package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * MyFamilyTube: optional content rules (parental control).
 * With no rules set (upstream SmartTube) every call is a no-op.
 */
public final class ContentGate {
    public interface Rules {
        /** Whether the video may appear in lists. */
        boolean allows(Video video);

        /** Whether the video may start playing. Shows its own message when it says no. */
        boolean allowsPlayback(Context context, Video video);

        /** Whether Shorts are shown to the profile watching now. */
        boolean allowsShorts();
    }

    private static Rules sRules;

    private ContentGate() {
    }

    public static void setRules(Rules rules) {
        sRules = rules;
    }

    public static boolean allows(Video video) {
        Rules rules = sRules;
        return rules == null || video == null || rules.allows(video);
    }

    public static boolean allowsPlayback(Context context, Video video) {
        Rules rules = sRules;
        return rules == null || video == null || rules.allowsPlayback(context, video);
    }

    public static boolean allowsShorts() {
        Rules rules = sRules;
        return rules == null || rules.allowsShorts();
    }

    /** Removes from the group, in place, the videos the rules don't allow. */
    public static void filter(VideoGroup group) {
        Rules rules = sRules;
        if (rules == null || group == null || group.getVideos() == null) {
            return;
        }
        // A whole "Shorts" shelf (e.g. on a channel page) goes, even if its videos aren't marked one by one
        boolean shortsShelf = !rules.allowsShorts()
                && group.getTitle() != null && group.getTitle().trim().equalsIgnoreCase("shorts");
        List<Video> videos = new ArrayList<>(group.getVideos());
        for (Video video : videos) {
            if (video != null && (shortsShelf || !rules.allows(video))) {
                group.remove(video);
            }
        }
    }
}
