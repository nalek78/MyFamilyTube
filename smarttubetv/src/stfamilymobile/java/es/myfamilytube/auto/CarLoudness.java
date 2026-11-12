package es.myfamilytube.auto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Same level for every song, like YouTube does on its website: YouTube measures how loud each
 * song is (in dB above its reference level) and songs louder than that are turned down to it.
 * Quiet songs stay at full volume (turning them up would distort).
 */
final class CarLoudness {
    private static final Map<String, Float> sLoudnessDb = new HashMap<>();

    private CarLoudness() {
    }

    /** Remembers the song's loudness from YouTube's data (and saves it next to the saved song). */
    static void record(String videoId, Object formatInfo, File songDir) {
        Float db = readLoudnessDb(formatInfo);
        if (videoId == null || db == null) {
            return;
        }
        synchronized (sLoudnessDb) {
            sLoudnessDb.put(videoId, db);
        }
        if (songDir != null) {
            try (FileOutputStream out = new FileOutputStream(new File(songDir, videoId + ".lvl"))) {
                out.write(String.valueOf(db).getBytes("UTF-8"));
            } catch (Exception e) {
                // only the precise level is lost
            }
        }
    }

    /**
     * @param engineVolumeLevel the engine's own estimate (Video.volume), used when the exact
     *                          loudness isn't known
     */
    static float gain(String videoId, float engineVolumeLevel, File songDir) {
        Float db;
        synchronized (sLoudnessDb) {
            db = videoId != null ? sLoudnessDb.get(videoId) : null;
        }
        if (db == null && videoId != null && songDir != null) {
            db = readSaved(new File(songDir, videoId + ".lvl"));
        }
        if (db == null) {
            db = fromEngineLevel(engineVolumeLevel);
        }
        if (db == null || db <= 0) {
            return 1.0f;
        }
        float gain = (float) Math.pow(10.0, -db / 20.0);
        return Math.max(0.2f, Math.min(1.0f, gain));
    }

    /** YouTube's exact value (the engine keeps it but doesn't expose it). */
    private static Float readLoudnessDb(Object formatInfo) {
        if (formatInfo == null) {
            return null;
        }
        try {
            java.lang.reflect.Field field = formatInfo.getClass().getDeclaredField("mLoudnessDb");
            field.setAccessible(true);
            float db = field.getFloat(formatInfo);
            return db != 0 ? db : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** The engine's level is 1 - 10^(dB/20) / 2 (louder songs, lower level); 0.5 means "unknown". */
    private static Float fromEngineLevel(float level) {
        if (level <= 0 || level >= 1 || Math.abs(level - 0.5f) < 0.001f) {
            return null;
        }
        double normal = 2.0 - 2.0 * level;
        return normal > 0 ? (float) (20.0 * Math.log10(normal)) : null;
    }

    private static Float readSaved(File file) {
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[32];
            int read = in.read(bytes);
            return read > 0 ? Float.parseFloat(new String(bytes, 0, read, "UTF-8").trim()) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
