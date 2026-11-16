package es.myfamilytube.auto;

import android.text.TextUtils;

import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Where to download a song's sound from, for what needs a direct link (the Chromecast and the
 * songs saved for places without coverage). YouTube now often gives the sound only through SABR
 * (no link) and the regular file answers 403, so several ways are tried, each one checked with
 * YouTube before using it:
 * 1. the sound with its own link from the engine's answer;
 * 2. the same video asked as YouTube's VR app (ANDROID_VR), whose links need no extra tokens;
 * 3. the regular file (picture and sound together).
 */
final class SongSource {
    interface Log {
        void log(String text);
    }

    final String url;
    final String mimeType;
    final long length;
    final String userAgent;
    final String how;

    private SongSource(String url, String mimeType, long length, String userAgent, String how) {
        this.url = url;
        this.mimeType = mimeType;
        this.length = length;
        this.userAgent = userAgent;
        this.how = how;
    }

    String baseMime() {
        int semicolon = mimeType.indexOf(';');
        return (semicolon > 0 ? mimeType.substring(0, semicolon) : mimeType).trim();
    }

    boolean isWebm() {
        return mimeType.contains("webm");
    }

    boolean hasPicture() {
        return mimeType.startsWith("video/");
    }

    // ---------------------------------------------------------------- ANDROID_VR (same values as the engine's AppClient)

    private static final String VR_VERSION = "1.65.10";
    private static final String VR_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";

    /**
     * @param preferAac the Chromecast: AAC (every Cast device plays it). The saved songs: the best
     *                  quality (usually Opus).
     */
    static SongSource find(String videoId, boolean preferAac, Log log) {
        StringBuilder tried = new StringBuilder();
        MediaItemFormatInfo info = null;
        try {
            info = YouTubeServiceManager.instance().getMediaItemService().getFormatInfo(videoId);
        } catch (Exception e) {
            tried.append("motor: ").append(e.getClass().getSimpleName()).append(" · ");
        }
        if (info != null && info.isLive()) {
            log.log("Fuente: es un directo, no se puede enviar ni guardar");
            return null;
        }
        // 1) the engine's sound with a link
        if (info != null && !info.isUnplayable()) {
            for (MediaFormat format : sorted(info.getAdaptiveFormats(), preferAac)) {
                SongSource source = check(format.getUrl(), format.getMimeType(), DefaultHeaders.APP_USER_AGENT, "sonido del motor");
                if (source != null) {
                    return source;
                }
                tried.append("motor 403 · ");
                break; // the others from the same answer will be the same
            }
        }
        // 2) asked as the VR app
        try {
            SongSource source = fromVr(videoId, preferAac, tried);
            if (source != null) {
                return source;
            }
        } catch (Exception e) {
            tried.append("VR: ").append(e.getClass().getSimpleName()).append(" · ");
        }
        // 3) the regular file (picture and sound)
        if (info != null && info.getUrlFormats() != null) {
            for (MediaFormat format : info.getUrlFormats()) {
                if (format == null || format.getMimeType() == null || !format.getMimeType().contains("mp4")) {
                    continue;
                }
                SongSource source = check(format.getUrl(), format.getMimeType(), DefaultHeaders.APP_USER_AGENT, "fichero normal");
                if (source != null) {
                    return source;
                }
                tried.append("normal 403 · ");
                break;
            }
        }
        log.log("Fuente: ninguna vía funciona · " + tried
                + (info == null ? "sin respuesta del motor" : info.isUnplayable() ? "no reproducible: " + info.getPlayabilityReason() : ""));
        return null;
    }

    /** Sound-only formats with a link, the preferred first. */
    private static List<MediaFormat> sorted(List<MediaFormat> formats, boolean preferAac) {
        List<MediaFormat> result = new ArrayList<>();
        if (formats == null) {
            return result;
        }
        for (MediaFormat format : formats) {
            if (format != null && format.getMimeType() != null && format.getMimeType().startsWith("audio/")
                    && !TextUtils.isEmpty(format.getUrl()) && !format.isDrc()
                    && (format.getLanguage() == null || format.getLanguage().contains("original") || !hasOriginal(formats))) {
                result.add(format);
            }
        }
        java.util.Collections.sort(result, (a, b) -> Long.compare(score(b.getMimeType(), parse(b.getBitrate()), preferAac),
                score(a.getMimeType(), parse(a.getBitrate()), preferAac)));
        return result;
    }

    private static boolean hasOriginal(List<MediaFormat> formats) {
        for (MediaFormat format : formats) {
            if (format != null && format.getLanguage() != null && format.getLanguage().contains("original")) {
                return true;
            }
        }
        return false;
    }

    private static long score(String mime, long bitrate, boolean preferAac) {
        boolean aac = mime != null && mime.contains("mp4");
        return (preferAac == aac ? 1_000_000_000L : 0) + bitrate;
    }

    private static SongSource fromVr(String videoId, boolean preferAac, StringBuilder tried) throws Exception {
        String body = "{\"context\":{\"client\":{\"clientName\":\"ANDROID_VR\",\"clientVersion\":\"" + VR_VERSION + "\","
                + "\"androidSdkVersion\":32,\"osName\":\"Android\",\"osVersion\":\"12\",\"deviceMake\":\"Oculus\","
                + "\"deviceModel\":\"Quest 3\",\"hl\":\"es\",\"gl\":\"ES\"}},\"videoId\":\"" + videoId + "\","
                + "\"contentCheckOk\":true,\"racyCheckOk\":true}";
        HttpURLConnection connection = (HttpURLConnection) new URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", VR_USER_AGENT);
        connection.setRequestProperty("X-YouTube-Client-Name", "28");
        connection.setRequestProperty("X-YouTube-Client-Version", VR_VERSION);
        JSONObject answer;
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes("UTF-8"));
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                tried.append("VR responde ").append(code).append(" · ");
                return null;
            }
            answer = new JSONObject(read(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
        JSONObject status = answer.optJSONObject("playabilityStatus");
        if (status != null && !"OK".equals(status.optString("status"))) {
            tried.append("VR: ").append(status.optString("status")).append(" ").append(status.optString("reason")).append(" · ");
            return null;
        }
        JSONObject streaming = answer.optJSONObject("streamingData");
        JSONArray formats = streaming != null ? streaming.optJSONArray("adaptiveFormats") : null;
        if (formats == null) {
            tried.append("VR sin formatos · ");
            return null;
        }
        List<JSONObject> audio = new ArrayList<>();
        boolean hasOriginal = false;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null || !format.optString("mimeType").startsWith("audio/") || format.optString("url", "").isEmpty()
                    || format.optBoolean("isDrc")) {
                continue;
            }
            audio.add(format);
            JSONObject track = format.optJSONObject("audioTrack");
            if (track != null && track.optBoolean("audioIsDefault")) {
                hasOriginal = true;
            }
        }
        JSONObject best = null;
        long bestScore = -1;
        for (JSONObject format : audio) {
            JSONObject track = format.optJSONObject("audioTrack");
            if (hasOriginal && (track == null || !track.optBoolean("audioIsDefault"))) {
                continue;
            }
            long score = score(format.optString("mimeType"), format.optLong("bitrate"), preferAac);
            if (score > bestScore) {
                best = format;
                bestScore = score;
            }
        }
        if (best == null) {
            tried.append("VR sin sonido con enlace · ");
            return null;
        }
        SongSource source = check(best.optString("url"), best.optString("mimeType"), VR_USER_AGENT, "cliente VR");
        if (source == null) {
            tried.append("VR 403 · ");
        }
        return source;
    }

    /** Asks YouTube for the first byte: if it answers, the link works (and the total size comes with it). */
    private static SongSource check(String url, String mime, String userAgent, String how) {
        if (TextUtils.isEmpty(url) || mime == null) {
            return null;
        }
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Range", "bytes=0-0");
            try {
                int code = connection.getResponseCode();
                if (code != 200 && code != 206) {
                    return null;
                }
                long length = 0;
                String range = connection.getHeaderField("Content-Range");
                if (range != null && range.contains("/")) {
                    length = parse(range.substring(range.lastIndexOf('/') + 1).trim());
                } else if (code == 200) {
                    length = connection.getContentLength();
                }
                return length > 0 ? new SongSource(url, mime, length, userAgent, how) : null;
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static long parse(String value) {
        try {
            return value != null ? Long.parseLong(value) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }
}
