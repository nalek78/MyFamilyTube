package es.myfamilytube;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A small log kept on the TV to find out why the app closes or goes slow:
 * how the app last ended (crash, frozen, out of memory…), Java errors, memory warnings and load times.
 */
public final class FamilyDiagnostics {
    private static final String LOG_FILE = "diagnostics.log";
    private static final int MAX_BYTES = 48 * 1024;
    private static final String PREFS = "family_diagnostics";
    private static final String KEY_LAST_EXIT = "last_exit_checked";
    private static final String KEY_PENDING = "pending_exit";
    private static FamilyDiagnostics sInstance;

    private final Context mContext;
    private final File mFile;
    private final SimpleDateFormat mTime = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US);

    private FamilyDiagnostics(Context context) {
        mContext = context.getApplicationContext();
        mFile = new File(mContext.getFilesDir(), LOG_FILE);
    }

    public static synchronized FamilyDiagnostics instance(Context context) {
        if (sInstance == null) {
            sInstance = new FamilyDiagnostics(context);
        }
        return sInstance;
    }

    /** Appends one line with the time. Never throws. */
    public synchronized void log(String message) {
        try {
            if (mFile.length() > MAX_BYTES) {
                trim();
            }
            FileOutputStream out = new FileOutputStream(mFile, true);
            try {
                out.write((mTime.format(new Date()) + "  " + message + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // logging must never break the app
        }
    }

    /** Keeps the newest half of the log. */
    private void trim() throws IOException {
        String text = readFile();
        String kept = text.substring(text.length() / 2);
        int nl = kept.indexOf('\n');
        if (nl >= 0) {
            kept = kept.substring(nl + 1);
        }
        FileOutputStream out = new FileOutputStream(mFile, false);
        try {
            out.write(kept.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private String readFile() throws IOException {
        if (!mFile.exists()) {
            return "";
        }
        FileInputStream in = new FileInputStream(mFile);
        try {
            byte[] data = new byte[(int) mFile.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(data, 0, read, "UTF-8");
        } finally {
            in.close();
        }
    }

    public synchronized void clear() {
        //noinspection ResultOfMethodCallIgnored
        mFile.delete();
    }

    /** Newest lines first part of the screen: the last {@code maxLines} lines. */
    public synchronized List<String> lastLines(int maxLines) {
        List<String> lines = new ArrayList<>();
        try {
            for (String line : readFile().split("\n")) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            lines.add("No se pudo leer el registro: " + e.getMessage());
        }
        int from = Math.max(0, lines.size() - maxLines);
        return new ArrayList<>(lines.subList(from, lines.size()));
    }

    // ---------------------------------------------------------------- device

    public String deviceSummary() {
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        StringBuilder sb = new StringBuilder();
        sb.append("MyFamilyTube ").append(BuildConfig.VERSION_NAME)
                .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        if (am != null) {
            am.getMemoryInfo(mem);
            sb.append("RAM ").append(mem.totalMem / (1024 * 1024)).append(" MB, libre ")
                    .append(mem.availMem / (1024 * 1024)).append(" MB")
                    .append(mem.lowMemory ? " (¡poca memoria!)" : "")
                    .append(" · límite app ").append(am.getMemoryClass()).append("/").append(am.getLargeMemoryClass()).append(" MB")
                    .append(am.isLowRamDevice() ? " · equipo de poca RAM" : "");
        }
        Runtime rt = Runtime.getRuntime();
        sb.append(" · usada ").append((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).append(" MB");
        return sb.toString();
    }

    public boolean isLowMemoryDevice() {
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mem);
        return am.isLowRamDevice() || am.getMemoryClass() <= 192 || mem.totalMem < 2500L * 1024 * 1024;
    }

    // ---------------------------------------------------------------- how the app ended last time

    /**
     * Reads, once, the system's record of how the app ended since the last start (Android 11+).
     * Logs every exit; returns a message when one happened while the app was on screen.
     */
    public String checkPreviousExits() {
        if (Build.VERSION.SDK_INT < 30) {
            return null;
        }
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastChecked = prefs.getLong(KEY_LAST_EXIT, 0);
        long newest = lastChecked;
        String visibleProblem = null;
        try {
            ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(mContext.getPackageName(), 0, 8);
            // Oldest first, so the log reads in order
            for (int i = exits.size() - 1; i >= 0; i--) {
                ApplicationExitInfo exit = exits.get(i);
                if (exit.getTimestamp() <= lastChecked) {
                    continue;
                }
                newest = Math.max(newest, exit.getTimestamp());
                boolean onScreen = exit.getImportance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                String reason = reasonText(exit.getReason());
                StringBuilder line = new StringBuilder("CIERRE ").append(mTime.format(new Date(exit.getTimestamp())))
                        .append(": ").append(reason)
                        .append(onScreen ? " [en pantalla]" : " [en segundo plano]")
                        .append(" · memoria ").append(exit.getPss() / 1024).append(" MB");
                if (exit.getDescription() != null) {
                    line.append(" · ").append(exit.getDescription());
                }
                log(line.toString());
                if (exit.getReason() == ApplicationExitInfo.REASON_ANR || exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    String trace = readTrace(exit);
                    if (trace != null) {
                        log("  detalle: " + trace);
                    }
                }
                if (onScreen && isProblem(exit.getReason())) {
                    visibleProblem = reason;
                }
            }
        } catch (Throwable e) {
            log("No se pudo leer cómo terminó la app: " + e);
        }
        prefs.edit().putLong(KEY_LAST_EXIT, newest).apply();
        return visibleProblem;
    }

    private static boolean isProblem(int reason) {
        return reason == ApplicationExitInfo.REASON_CRASH || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                || reason == ApplicationExitInfo.REASON_ANR || reason == ApplicationExitInfo.REASON_LOW_MEMORY
                || reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                || reason == ApplicationExitInfo.REASON_SIGNALED || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE;
    }

    private static String reasonText(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: return "error de la app";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "error interno (nativo)";
            case ApplicationExitInfo.REASON_ANR: return "la app se quedó bloqueada";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "la tele se quedó sin memoria";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "demasiados recursos";
            case ApplicationExitInfo.REASON_SIGNALED: return "cerrada por el sistema (señal)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "fallo al arrancar";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "cerrada por la propia app";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "cerrada por el usuario";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "detenida por el usuario";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "cambio de permisos";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "falló otra app de la que depende";
            case ApplicationExitInfo.REASON_OTHER: return "cerrada por el sistema";
            default: return "motivo " + reason;
        }
    }

    private static String readTrace(ApplicationExitInfo exit) {
        try {
            InputStream stream = exit.getTraceInputStream();
            if (stream == null) {
                return null;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            try {
                StringBuilder sb = new StringBuilder();
                String line;
                boolean inMain = false;
                int kept = 0;
                // For a frozen app, the interesting part is what the main thread was doing
                while ((line = reader.readLine()) != null && kept < 14) {
                    if (line.startsWith("\"main\"")) {
                        inMain = true;
                    } else if (inMain && line.trim().isEmpty()) {
                        break;
                    }
                    if (inMain && line.contains("at ")) {
                        sb.append(line.trim()).append(" | ");
                        kept++;
                    }
                }
                return sb.length() > 0 ? sb.toString() : null;
            } finally {
                reader.close();
            }
        } catch (Throwable e) {
            return null;
        }
    }

    /** Remembers that a visible problem should be shown on the next screen. */
    public void setPendingProblem(String text) {
        mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING, text).apply();
    }

    public String takePendingProblem() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String text = prefs.getString(KEY_PENDING, null);
        prefs.edit().remove(KEY_PENDING).apply();
        return text;
    }
}
