package es.myfamilytube.auto;



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
    private final Map<String, SongSource> mFormats = new ConcurrentHashMap<>();
    private ServerSocket mServer;
    private final java.util.Set<String> mNoLink = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private android.content.Context mContext;
    private SongCapture mCapture;

    void setContext(android.content.Context context) {
        mContext = context.getApplicationContext();
    }

    /** The song played by the phone's own player into a file (when YouTube gives no link). */
    private synchronized SongCapture capture(String videoId) {
        if (mCapture != null && mCapture.videoId.equals(videoId)) {
            return mCapture;
        }
        if (mCapture != null) {
            mCapture.release();
            mCapture = null;
        }
        if (mContext == null) {
            return null;
        }
        mCapture = new SongCapture(mContext, videoId, new java.io.File(new java.io.File(mContext.getCacheDir(), "cast"), videoId + ".wav"), text -> mLog.log("Cast: " + text));
        return mCapture;
    }

    /** Gets the song ready (slow: YouTube is asked) and gives its address for the Chromecast. */
    void prepare(String videoId, Ready ready) {
        mPool.execute(() -> {
            String url = null;
            String mime = null;
            try {
                SongSource source = source(videoId);
                String address = wifiAddress();
                if (source == null && address != null) {
                    SongCapture capture = capture(videoId);
                    long length = capture != null ? capture.waitLength(40_000) : 0;
                    if (length > 0) {
                        start();
                        url = "http://" + address + ":" + mServer.getLocalPort() + "/song/" + videoId;
                        mime = "audio/wav";
                        mLog.log("Cast: canción lista (captura) en " + url + " (" + length / 1024 + " KB)");
                    } else {
                        mLog.log("Cast: tampoco se pudo con el reproductor del móvil");
                    }
                } else if (source != null && address != null) {
                    start();
                    url = "http://" + address + ":" + mServer.getLocalPort() + "/song/" + videoId;
                    mime = source.baseMime();
                    mLog.log("Cast: canción lista en " + url + " (" + source.length / 1024 + " KB)");
                } else {
                    mLog.log("Cast: no se puede enviar · sonido " + (source != null ? "sí" : "NO") + " · Wi‑Fi " + (address != null ? address : "NO"));
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
        mNoLink.clear();
        if (mCapture != null) {
            mCapture.release();
            mCapture = null;
        }
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

    /** The sound to send (AAC, every Cast device plays it), checked with YouTube before using it. */
    private SongSource source(String videoId) {
        SongSource known = mFormats.get(videoId);
        if (known != null) {
            return known;
        }
        if (mNoLink.contains(videoId)) {
            return null;
        }
        SongSource source = SongSource.find(videoId, true, text -> mLog.log("Cast: " + text));
        if (source != null) {
            mLog.log("Cast: sonido por " + source.how + " (" + source.mimeType + ")");
            mFormats.put(videoId, source);
        } else {
            mNoLink.add(videoId);
        }
        return source;
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
            SongSource source = source(videoId);
            SongCapture capture = source == null ? capture(videoId) : null;
            long total = source != null ? source.length : capture != null ? capture.waitLength(40_000) : 0;
            if (total <= 0) {
                answer(out, "404 Not Found", null, 0, null, null);
                return;
            }
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
            answer(out, partial ? "206 Partial Content" : "200 OK", source != null ? source.baseMime() : "audio/wav", end - start + 1, null,
                    partial ? "bytes " + start + "-" + end + "/" + total : null);
            if (!head) {
                if (source != null) {
                    copy(videoId, source, start, end, out);
                } else {
                    byte[] buffer = new byte[64 * 1024];
                    long position = start;
                    while (position <= end) {
                        int read = capture.read(position, buffer, (int) Math.min(buffer.length, end - position + 1));
                        if (read < 0) {
                            break;
                        }
                        out.write(buffer, 0, read);
                        position += read;
                    }
                    out.flush();
                }
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
    private void copy(String videoId, SongSource source, long start, long end, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long position = start;
        boolean retried = false;
        while (position <= end) {
            long chunkEnd = Math.min(end, position + CHUNK - 1);
            HttpURLConnection connection = (HttpURLConnection) new URL(source.url).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", source.userAgent);
            connection.setRequestProperty("Range", "bytes=" + position + "-" + chunkEnd);
            try {
                int code = connection.getResponseCode();
                if ((code == 403 || code == 410) && !retried) {
                    retried = true; // the link expired: a new one
                    mFormats.remove(videoId);
                    SongSource fresh = source(videoId);
                    if (fresh == null) {
                        return;
                    }
                    source = fresh;
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
