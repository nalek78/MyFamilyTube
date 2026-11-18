package es.myfamilytube.auto;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orders the songs of a search the way YouTube Music does: first the official song (the
 * artist's own channel, «- Topic», VEVO, «oficial»…), then the rest as YouTube gave them, and at
 * the end the other versions (covers, live, karaoke, remixes…). Among the official ones, the
 * newest first.
 */
final class SearchRank {
    private static final Pattern OFFICIAL = Pattern.compile(
            "\\b(official|oficial|officiel|ufficiale)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile(
            "\\b(cover|karaoke|live|en vivo|en directo|directo|remix|rmx|instrumental|8d|sped up|slowed|nightcore|reaction|reacci[oó]n|"
                    + "tutorial|lesson|clase|piano version|guitar|guitarra|acoustic|ac[uú]stico|unplugged|parody|parodia|tribute|tributo|"
                    + "mashup|medley|bass boosted|1 hour|1 hora|loop)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AGE = Pattern.compile(
            "(?:hace\\s+)?(\\d+)\\s*(segundo|second|minuto|minute|hora|hour|d[ií]a|day|semana|week|mes|month|a[nñ]o|year)",
            Pattern.CASE_INSENSITIVE);

    /** What clearly isn't a song: reactions, interviews, tutorials, trailers, news… */
    private static final Pattern NOT_MUSIC = Pattern.compile(
            "\\b(reacci[oó]n|reacciona|reaction|reacting|entrevista|interview|tutorial|c[oó]mo tocar|how to|gameplay|trailer|tr[aá]iler|"
                    + "podcast|vlog|unboxing|review|rese[nñ]a|noticias|news|documental|documentary|explicaci[oó]n|explained|"
                    + "an[aá]lisis|analysis|behind the scenes|detr[aá]s de c[aá]maras|making of|teaser|broma|prank|challenge|reto|"
                    + "capitulo|cap[ií]tulo|episodio|episode|programa completo|full episode|stream|directo de twitch|minecraft|fortnite)\\b",
            Pattern.CASE_INSENSITIVE);

    private SearchRank() {
    }

    static boolean looksLikeMusic(Video video) {
        String title = video.getTitle() != null ? video.getTitle() : "";
        return !NOT_MUSIC.matcher(title).find();
    }

    static List<Video> sort(List<Video> videos) {
        List<Video> official = new ArrayList<>();
        List<Video> normal = new ArrayList<>();
        List<Video> versions = new ArrayList<>();
        for (Video video : videos) {
            String title = video.getTitle() != null ? video.getTitle() : "";
            if (VERSION.matcher(title).find()) {
                versions.add(video);
            } else if (isOfficial(video, title)) {
                official.add(video);
            } else {
                normal.add(video);
            }
        }
        // stable: two equally new keep YouTube's order
        Collections.sort(official, (a, b) -> Long.compare(ageSeconds(a), ageSeconds(b)));
        List<Video> result = new ArrayList<>(official);
        result.addAll(normal);
        result.addAll(versions);
        return result;
    }

    private static boolean isOfficial(Video video, String title) {
        String author = video.getAuthor() != null ? video.getAuthor() : "";
        if (author.endsWith(" - Topic") || author.toUpperCase(Locale.ROOT).contains("VEVO")) {
            return true;
        }
        if (OFFICIAL.matcher(title).find() || OFFICIAL.matcher(author).find()) {
            return true;
        }
        // «Artist - Song» uploaded by the artist's own channel
        MusicArt.Info info = MusicArt.guess(title, author);
        if (info != null && info.artist != null && !author.isEmpty()) {
            String artist = MusicArt.normalize(info.artist);
            String channel = MusicArt.normalize(author);
            return !artist.isEmpty() && (channel.equals(artist) || channel.startsWith(artist + " ") || artist.startsWith(channel + " "));
        }
        return false;
    }

    /** How old the video is, from «hace 3 años» / «3 years ago». Unknown: very old. */
    private static long ageSeconds(Video video) {
        String text = "";
        if (video.mediaItem != null && video.mediaItem.getProductionDate() != null) {
            text = video.mediaItem.getProductionDate();
        }
        if (video.secondTitle != null) {
            text += " " + video.secondTitle;
        }
        Matcher matcher = AGE.matcher(text);
        if (!matcher.find()) {
            return Long.MAX_VALUE;
        }
        long number = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        long seconds;
        if (unit.startsWith("seg") || unit.startsWith("sec")) {
            seconds = 1;
        } else if (unit.startsWith("min")) {
            seconds = 60;
        } else if (unit.startsWith("hor") || unit.startsWith("hou")) {
            seconds = 3600;
        } else if (unit.startsWith("d")) {
            seconds = 86400;
        } else if (unit.startsWith("sem") || unit.startsWith("wee")) {
            seconds = 7 * 86400;
        } else if (unit.startsWith("mes") || unit.startsWith("mon")) {
            seconds = 30 * 86400;
        } else {
            seconds = 365 * 86400;
        }
        return number * seconds;
    }
}
