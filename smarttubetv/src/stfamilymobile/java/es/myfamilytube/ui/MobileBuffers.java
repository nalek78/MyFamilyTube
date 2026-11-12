package es.myfamilytube.ui;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;

/**
 * How much of a video/song is downloaded ahead, so a weak signal doesn't cut it.
 * Phones have more memory than the TV, so they can keep more.
 */
public final class MobileBuffers {
    private MobileBuffers() {
    }

    /** Phone player: follows Ajustes › «Memoria para el vídeo» (Baja / Normal / Alta). */
    public static DefaultLoadControl video(int bufferType, int maxBytes) {
        int min;
        int max;
        int back;
        switch (bufferType) {
            case PlayerConstants.BUFFER_LOW:
                min = 15_000;
                max = 30_000;
                back = 0;
                break;
            case PlayerConstants.BUFFER_HIGH:
            case PlayerConstants.BUFFER_HIGHEST:
                min = 120_000; // 2 min ahead...
                max = 300_000; // ...up to 5 min
                back = 30_000;
                break;
            default:
                min = 60_000; // 1 min ahead...
                max = 150_000; // ...up to 2½ min
                back = 15_000;
                break;
        }
        DefaultLoadControl.Builder builder = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(min, max, 2_500, 5_000)
                .setTargetBufferBytes(maxBytes); // never more than the phone can spare
        if (back > 0) {
            builder.setBackBuffer(back, true);
        }
        return builder.createDefaultLoadControl();
    }

    /**
     * Songs in the car (sound only, ~1 MB per minute): the whole song is downloaded as fast as
     * the connection allows, so tunnels and dead spots don't stop it. The last 2 min are kept
     * to go back without downloading again.
     */
    public static DefaultLoadControl song() {
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20 * 60_000, 20 * 60_000, 1_500, 4_000)
                .setTargetBufferBytes(48 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(120_000, true)
                .createDefaultLoadControl();
    }
}
