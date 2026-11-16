package es.myfamilytube.auto;

import android.text.TextUtils;

import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone hands the songs to the Chromecast over the home Wi-Fi. The Chromecast can't use
 * YouTube's links itself (they are only for this app), so it asks the phone
 * (http://phone:port/song/ID) and the phone brings the sound from YouTube piece by piece as it is
 * asked for (it can start at once and jump to any point). AAC (m4a), which every Cast device plays.
 */
final class CastBridge {
    private static final int CHUNK = 1024 * 1024; // YouTube serves sound faster in pieces

    interface Ready {
        /** url null: the phone isn't on a Wi-Fi network (or the song can't be sent). */
        void onReady(String url, String mimeType);
    }

    /** Notes for the app's «Diagnóstico» (there is no other way to see what happened on a phone). */
    interface Log {
        void log(String text);
    }

    private Log mLog = text -> { };

    void setLog(Log log) {
        mLog = log;
    }

    private final ExecutorService mPool = Executors.newCachedThreadPool();
    private final Map<String, MediaFormat> mFormats = new ConcurrentHashMap<>();
    private ServerSocket mServer;

    /** Gets the song ready (slow: YouTube is asked) and gives its address for the Chromecast. */
    void prepare(String videoId, Ready ready) {
        mPool.execute(() -> {
            String url = null;
            String mime = null;
            try {
                MediaFormat format = format(videoId);
                String address = wifiAddress();
                if (format != null && address != null) {
                    start();
                    url = "http://" + address + ":" + mServer.getLocalPort() + "/song/" + videoId;
                    mime = baseMime(format);
                    mLog.log("Cast: canción lista en " + url + " (" + format.getMimeType() + ", " + length(format) / 1024 + " KB)");
                } else {
                    mLog.log("Cast: no se puede enviar · formato " + (format != null ? "sí" : "NO") + " · Wi‑Fi " + (address != null ? address : "NO"));
                }
            } catch (Exception e) {
                mLog.log("Cast: error al preparar la canción: " + e);
                url = null;
            }
            ready.onReady(url, mime);
        });
    }

    synchronized void stop() {
        if (mServer != null) {
            try {
                mServer.close();
            } catch (IOException e) {
                // closed
            }
            mServer = null;
        }
        mFormats.clear();
    }

    private synchronized void start() throws IOException {
        if (mServer != null && !mServer.isClosed()) {
            return;
        }
        ServerSocket server = new ServerSocket(0);
        mServer = server;
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    mPool.execute(() -> handle(socket));
                } catch (IOException e) {
                    // closed, or a bad connection
                }
            }
        }, "CastBridge");
        thread.setDaemon(true);
        thread.start();
    }

    /** The phone's address on the Wi-Fi (the Chromecast must be on the same network). */
    static String wifiAddress() {
        try {
            String fallback = null;
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        String name = network.getName() != null ? network.getName() : "";
                        if (name.startsWith("wlan") || name.startsWith("eth")) {
                            return address.getHostAddress();
                        }
                        if (fallback == null && !name.startsWith("rmnet") && !name.startsWith("ccmni") && !name.startsWith("tun")) {
                            fallback = address.getHostAddress();
                        }
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    /** The sound to send: AAC (all Cast devices play it), the original language, best quality. */
    private MediaFormat format(String videoId) throws Exception {
        MediaFormat known = mFormats.get(videoId);
        if (known != null) {
            return known;
        }
        MediaItemFormatInfo info = YouTubeServiceManager.instance().getMediaItemService().getFormatInfo(videoId);
        if (info == null || info.isUnplayable() || info.isLive()) {
            mLog.log("Cast: YouTube no da la canción · " + (info == null ? "sin respuesta"
                    : info.isLive() ? "es un directo" : "no se puede reproducir: " + info.getPlayabilityReason()));
            return null;
        }
        // 1) only the sound, with its own link (best quality)
        MediaFormat format = pick(info.getAdaptiveFormats());
        // 2) YouTube now often gives the sound only through SABR (no link): the regular file
        //    with picture and sound together (medium quality), which still has a link
        if (format == null) {
            format = pickRegular(info.getUrlFormats());
            if (format != null) {
                mLog.log("Cast: sin enlace al sonido solo (SABR); se envía el vídeo normal " + format.getMimeType());
            }
        }
        if (format == null) {
            mLog.log("Cast: ningún formato con enlace · " + describe(info));
            return null;
        }
        if (length(format) <= 0) {
            long probed = probeLength(format.getUrl());
            if (probed <= 0) {
                mLog.log("Cast: no se pudo saber el tamaño del fichero de YouTube");
                return null;
            }
            mLengths.put(format.getUrl(), probed);
        }
        mFormats.put(videoId, format);
        return format;
    }

    private final Map<String, Long> mLengths = new ConcurrentHashMap<>();

    /** For «Diagnóstico»: what YouTube sent. */
    private static String describe(MediaItemFormatInfo info) {
        int adaptive = 0;
        int adaptiveWithUrl = 0;
        int sabr = 0;
        if (info.getAdaptiveFormats() != null) {
            for (MediaFormat format : info.getAdaptiveFormats()) {
                adaptive++;
                if (format != null && !TextUtils.isEmpty(format.getUrl())) {
                    adaptiveWithUrl++;
                }
                if (format != null && format.getFormatType() == MediaFormat.FORMAT_TYPE_SABR) {
                    sabr++;
                }
            }
        }
        int regular = info.getUrlFormats() != null ? info.getUrlFormats().size() : 0;
        return "adaptativos " + adaptive + " (con enlace " + adaptiveWithUrl + ", SABR " + sabr + ") · normales " + regular
                + " · HLS " + (TextUtils.isEmpty(info.getHlsManifestUrl()) ? "no" : "sí");
    }

    /** The regular file (picture and sound together): mp4 first, the best one. */
    static MediaFormat pickRegular(List<MediaFormat> formats) {
        if (formats == null) {
            return null;
        }
        MediaFormat best = null;
        long bestScore = -1;
        for (MediaFormat format : formats) {
            if (format == null || TextUtils.isEmpty(format.getUrl()) || format.getMimeType() == null) {
                continue;
            }
            boolean mp4 = format.getMimeType().contains("mp4");
            long score = (mp4 ? 1_000_000_000L : 0) + parse(format.getBitrate());
            if (score > bestScore) {
                best = format;
                bestScore = score;
            }
        }
        return best;
    }

    /** The file's size, asking YouTube for its first byte (Content-Range: bytes 0-0/TOTAL). */
    static long probeLength(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", DefaultHeaders.APP_USER_AGENT);
            connection.setRequestProperty("Range", "bytes=0-0");
            try {
                String range = connection.getHeaderField("Content-Range");
                if (range != null && range.contains("/")) {
                    return parse(range.substring(range.lastIndexOf('/') + 1).trim());
                }
                return connection.getResponseCode() == 200 ? connection.getContentLength() : 0;
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static MediaFormat pick(List<MediaFormat> formats) {
        if (formats == null) {
            return null;
        }
        boolean hasOriginal = false;
        for (MediaFormat format : formats) {
            if (isAudio(format) && format.getLanguage() != null && format.getLanguage().contains("original")) {
                hasOriginal = true;
            }
        }
        MediaFormat best = null;
        long bestScore = -1;
        for (MediaFormat format : formats) {
            if (!isAudio(format) || clen(format) <= 0) {
                continue;
            }
            if (hasOriginal && (format.getLanguage() == null || !format.getLanguage().contains("original"))) {
                continue;
            }
            boolean aac = format.getMimeType().contains("mp4");
            long score = (aac ? 1_000_000_000L : 0) + parse(format.getBitrate());
            if (score > bestScore) {
                best = format;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean isAudio(MediaFormat format) {
        return format != null && format.getMimeType() != null && format.getMimeType().startsWith("audio/")
                && !TextUtils.isEmpty(format.getUrl()) && !format.isDrc();
    }

    private long length(MediaFormat format) {
        long clen = parse(format.getClen());
        if (clen > 0) {
            return clen;
        }
        Long probed = format.getUrl() != null ? mLengths.get(format.getUrl()) : null;
        return probed != null ? probed : 0;
    }

    private static long clen(MediaFormat format) {
        return parse(format.getClen());
    }

    private static long parse(String value) {
        try {
            return value != null ? Long.parseLong(value) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String baseMime(MediaFormat format) {
        String mime = format.getMimeType();
        int semicolon = mime.indexOf(';');
        return (semicolon > 0 ? mime.substring(0, semicolon) : mime).trim();
    }

    // ---------------------------------------------------------------- answering the Chromecast

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            String range = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Range")) {
                    range = line.substring(colon + 1).trim();
                }
            }
            String[] parts = requestLine.split(" ");
            OutputStream out = client.getOutputStream();
            mLog.log("Cast: petición de " + client.getInetAddress().getHostAddress() + " · " + requestLine + (range != null ? " · " + range : ""));
            if ("OPTIONS".equals(parts[0])) {
                out.write(("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
                        + "Access-Control-Allow-Headers: Range, Content-Type\r\nAccess-Control-Max-Age: 86400\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes("US-ASCII"));
                out.flush();
                return;
            }
            if (parts.length < 2 || !parts[1].startsWith("/song/")) {
                answer(out, "404 Not Found", null, 0, null, null);
                return;
            }
            boolean head = "HEAD".equals(parts[0]);
            String videoId = parts[1].substring("/song/".length());
            int query = videoId.indexOf('?');
            if (query >= 0) {
                videoId = videoId.substring(0, query);
            }
            MediaFormat format = format(videoId);
            if (format == null) {
                answer(out, "404 Not Found", null, 0, null, null);
                return;
            }
            long total = length(format);
            long start = 0;
            long end = total - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String spec = range.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                try {
                    if (dash == 0) { // the last N bytes
                        start = Math.max(0, total - Long.parseLong(spec.substring(1)));
                    } else if (dash > 0) {
                        start = Long.parseLong(spec.substring(0, dash));
                        if (dash < spec.length() - 1) {
                            end = Math.min(total - 1, Long.parseLong(spec.substring(dash + 1)));
                        }
                    }
                    partial = true;
                } catch (NumberFormatException e) {
                    start = 0;
                    end = total - 1;
                }
            }
            if (start > end || start >= total) {
                answer(out, "416 Range Not Satisfiable", null, 0, null, "bytes */" + total);
                return;
            }
            answer(out, partial ? "206 Partial Content" : "200 OK", baseMime(format), end - start + 1, null,
                    partial ? "bytes " + start + "-" + end + "/" + total : null);
            if (!head) {
                copy(videoId, format, start, end, out);
            }
        } catch (Exception e) {
            // the Chromecast closed the connection (a jump, or another song)
        }
    }

    private static void answer(OutputStream out, String status, String mime, long length, String extra, String contentRange) throws IOException {
        StringBuilder header = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n");
        if (mime != null) {
            header.append("Content-Type: ").append(mime).append("\r\n");
        }
        header.append("Content-Length: ").append(length).append("\r\n");
        header.append("Accept-Ranges: bytes\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        header.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n");
        if (contentRange != null) {
            header.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        header.append("Connection: close\r\n\r\n");
        out.write(header.toString().getBytes("US-ASCII"));
        out.flush();
    }

    /** From YouTube to the Chromecast, 1 MB at a time (YouTube's link is asked again if it expired). */
    private void copy(String videoId, MediaFormat format, long start, long end, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long position = start;
        boolean retried = false;
        while (position <= end) {
            long chunkEnd = Math.min(end, position + CHUNK - 1);
            HttpURLConnection connection = (HttpURLConnection) new URL(format.getUrl()).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", DefaultHeaders.APP_USER_AGENT);
            connection.setRequestProperty("Range", "bytes=" + position + "-" + chunkEnd);
            try {
                int code = connection.getResponseCode();
                if ((code == 403 || code == 410) && !retried) {
                    retried = true; // the link expired: a new one
                    mFormats.remove(videoId);
                    MediaFormat fresh = format(videoId);
                    if (fresh == null) {
                        return;
                    }
                    format = fresh;
                    continue;
                }
                if (code != 200 && code != 206) {
                    mLog.log("Cast: YouTube respondió " + code + " al pedir el sonido");
                    return;
                }
                try (InputStream in = connection.getInputStream()) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        position += read;
                    }
                }
            } finally {
                connection.disconnect();
            }
            if (position <= chunkEnd) {
                return; // YouTube sent less than asked: stop here
            }
        }
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.write(c);
            }
            if (line.size() > 8192) {
                break;
            }
        }
        if (c == -1 && line.size() == 0) {
            return null;
        }
        return line.toString("US-ASCII");
    }
}
