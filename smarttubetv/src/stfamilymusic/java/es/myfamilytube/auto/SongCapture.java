package es.myfamilytube.auto;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.AuxEffectInfo;
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * When YouTube gives no direct link to the sound (only SABR, which only this app's player
 * understands), the phone's own player opens the song, without playing it aloud, as fast as it
 * can, and its sound (uncompressed, WAV) is written to a file as it comes. The Chromecast is
 * given that file through the phone while it is still being written.
 */
final class SongCapture {
    private static final int HEADER = 44;

    final String videoId;
    private final File mFile;
    private final CastBridge.Log mLog;
    private final Object mLock = new Object();
    private HandlerThread mThread;
    private SimpleExoPlayer mPlayer;
    private RandomAccessFile mOut;
    private volatile long mWritten; // sound bytes written after the header
    private volatile long mDataSize; // sound bytes announced in the header (0 = not known yet)
    private volatile boolean mDone;
    private volatile boolean mFailed;
    private long mDurationUs;

    SongCapture(Context context, String videoId, File dir, CastBridge.Log log) {
        this.videoId = videoId;
        mLog = log;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        mFile = new File(dir, "cast_" + videoId + ".wav");
        start(context.getApplicationContext());
    }

    /** Whole size of the WAV (header + sound), waiting until it is known. 0: it failed. */
    long waitLength(long timeoutMs) {
        long until = System.currentTimeMillis() + timeoutMs;
        synchronized (mLock) {
            while (mDataSize == 0 && !mFailed && System.currentTimeMillis() < until) {
                try {
                    mLock.wait(250);
                } catch (InterruptedException e) {
                    return 0;
                }
            }
        }
        return mDataSize > 0 ? HEADER + mDataSize : 0;
    }

    /** Reads from the file, waiting for the sound still being written. -1: there won't be more. */
    int read(long position, byte[] buffer, int max) throws Exception {
        long total = HEADER + mDataSize;
        if (position >= total) {
            return -1;
        }
        synchronized (mLock) {
            while (!mDone && !mFailed && position >= HEADER + mWritten) {
                mLock.wait(250);
            }
        }
        long available = HEADER + mWritten;
        if (position < available) {
            int count = (int) Math.min(max, available - position);
            try (RandomAccessFile in = new RandomAccessFile(mFile, "r")) {
                in.seek(position);
                return in.read(buffer, 0, count);
            }
        }
        if (mFailed && !mDone) {
            return -1;
        }
        // finished a bit shorter than announced: silence until the end
        int count = (int) Math.min(max, total - position);
        java.util.Arrays.fill(buffer, 0, count, (byte) 0);
        return count;
    }

    void release() {
        HandlerThread thread = mThread;
        if (thread != null) {
            new Handler(thread.getLooper()).post(() -> {
                if (mPlayer != null) {
                    mPlayer.release();
                    mPlayer = null;
                }
                thread.quitSafely();
            });
            mThread = null;
        }
        mFailed = true;
        synchronized (mLock) {
            mLock.notifyAll();
        }
        closeFile();
        //noinspection ResultOfMethodCallIgnored
        mFile.delete();
    }

    private void fail(String why) {
        if (!mFailed) {
            mLog.log("captura: " + why);
        }
        mFailed = true;
        synchronized (mLock) {
            mLock.notifyAll();
        }
    }

    private void start(Context context) {
        mThread = new HandlerThread("SongCapture");
        mThread.start();
        Handler handler = new Handler(mThread.getLooper());
        handler.post(() -> {
            try {
                MediaItemFormatInfo info = YouTubeServiceManager.instance().getMediaItemService().getFormatInfo(videoId);
                if (info == null || info.isUnplayable()) {
                    fail("YouTube no da la canción" + (info != null ? ": " + info.getPlayabilityReason() : ""));
                    return;
                }
                try {
                    mDurationUs = Long.parseLong(info.getLengthSeconds()) * 1_000_000L;
                } catch (Exception e) {
                    mDurationUs = 0;
                }
                ExoMediaSourceFactory factory = new ExoMediaSourceFactory(context);
                MediaSource source = info.containsSabrFormats() ? factory.fromSabrFormatInfo(info) : factory.fromDashFormatInfo(info);
                mOut = new RandomAccessFile(mFile, "rw");
                mOut.setLength(0);
                RenderersFactory renderers = (eventHandler, videoListener, audioListener, textOutput, metadataOutput, drm) ->
                        new Renderer[] {new MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, null, false,
                                eventHandler, audioListener, new FileSink())};
                mPlayer = ExoPlayerFactory.newSimpleInstance(context, renderers, new DefaultTrackSelector());
                mPlayer.addListener(new Player.EventListener() {
                    @Override
                    public void onPlayerStateChanged(boolean playWhenReady, int state) {
                        if (state == Player.STATE_ENDED) {
                            finish();
                        }
                    }

                    @Override
                    public void onPlayerError(ExoPlaybackException error) {
                        fail("el reproductor falló: " + error.getCause());
                    }
                });
                mPlayer.setPlayWhenReady(true);
                mPlayer.prepare(source);
                mLog.log("captura: abriendo la canción con el reproductor del móvil (" + (info.containsSabrFormats() ? "SABR" : "DASH") + ")");
            } catch (Throwable e) {
                fail("no se pudo abrir: " + e);
            }
        });
    }

    private void finish() {
        if (mDone) {
            return;
        }
        mDone = true;
        mLog.log("captura: terminada · " + mWritten / 1024 + " KB de sonido");
        synchronized (mLock) {
            mLock.notifyAll();
        }
        closeFile();
        HandlerThread thread = mThread;
        if (thread != null && mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }

    private void closeFile() {
        synchronized (mLock) {
            if (mOut != null) {
                try {
                    mOut.close();
                } catch (Exception e) {
                    // closed
                }
                mOut = null;
            }
        }
    }

    private void writeHeader(int sampleRate, int channels) throws Exception {
        long bytesPerSecond = (long) sampleRate * channels * 2;
        long seconds = mDurationUs > 0 ? (mDurationUs + 999_999) / 1_000_000 + 1 : 600;
        long dataSize = seconds * bytesPerSecond;
        ByteBuffer header = ByteBuffer.allocate(HEADER).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes("US-ASCII")).putInt((int) (36 + dataSize)).put("WAVE".getBytes("US-ASCII"));
        header.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(sampleRate).putInt((int) bytesPerSecond).putShort((short) (channels * 2)).putShort((short) 16);
        header.put("data".getBytes("US-ASCII")).putInt((int) dataSize);
        synchronized (mLock) {
            mOut.seek(0);
            mOut.write(header.array());
            mDataSize = dataSize;
            mLock.notifyAll();
        }
        mLog.log("captura: sonido " + sampleRate + " Hz, " + channels + " canales, " + dataSize / 1024 + " KB previstos");
    }

    /** Takes the player's decoded sound and writes it to the file (instead of playing it). */
    private final class FileSink implements AudioSink {
        private PlaybackParameters mParameters = PlaybackParameters.DEFAULT;
        private int mFrameBytes = 4;
        private int mSampleRate = 44100;
        private long mStartUs = C.TIME_UNSET;
        private long mFramesSinceStart;
        private boolean mEnded;
        private boolean mHeaderWritten;

        @Override
        public void setListener(Listener listener) {
        }

        @Override
        public boolean supportsOutput(int channelCount, int encoding) {
            return encoding == C.ENCODING_PCM_16BIT;
        }

        @Override
        public long getCurrentPositionUs(boolean sourceEnded) {
            if (mStartUs == C.TIME_UNSET) {
                return CURRENT_POSITION_NOT_SET;
            }
            return mStartUs + mFramesSinceStart * 1_000_000L / mSampleRate;
        }

        @Override
        public void configure(int inputEncoding, int inputChannelCount, int inputSampleRate, int specifiedBufferSize,
                              int[] outputChannels, int trimStartFrames, int trimEndFrames) throws ConfigurationException {
            if (inputEncoding != C.ENCODING_PCM_16BIT) {
                throw new ConfigurationException("encoding " + inputEncoding);
            }
            mSampleRate = inputSampleRate;
            mFrameBytes = inputChannelCount * 2;
            if (!mHeaderWritten) {
                try {
                    writeHeader(inputSampleRate, inputChannelCount);
                    mHeaderWritten = true;
                } catch (Exception e) {
                    throw new ConfigurationException(e);
                }
            }
        }

        @Override
        public void play() {
        }

        @Override
        public void handleDiscontinuity() {
        }

        @Override
        public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs) throws WriteException {
            if (mStartUs == C.TIME_UNSET) {
                mStartUs = presentationTimeUs;
            }
            int size = buffer.remaining();
            if (size > 0) {
                byte[] bytes = new byte[size];
                buffer.get(bytes);
                synchronized (mLock) {
                    if (mOut != null && mDataSize > 0) {
                        long room = mDataSize - mWritten;
                        int count = (int) Math.min(size, Math.max(0, room));
                        if (count > 0) {
                            try {
                                mOut.seek(HEADER + mWritten);
                                mOut.write(bytes, 0, count);
                            } catch (Exception e) {
                                throw new WriteException(-1);
                            }
                            mWritten += count;
                        }
                    }
                    mLock.notifyAll();
                }
                mFramesSinceStart += size / mFrameBytes;
            }
            return true;
        }

        @Override
        public void playToEndOfStream() {
            mEnded = true;
        }

        @Override
        public boolean isEnded() {
            return mEnded;
        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public PlaybackParameters setPlaybackParameters(PlaybackParameters playbackParameters) {
            mParameters = playbackParameters;
            return mParameters;
        }

        @Override
        public PlaybackParameters getPlaybackParameters() {
            return mParameters;
        }

        @Override
        public void setAudioAttributes(AudioAttributes audioAttributes) {
        }

        @Override
        public void setAudioSessionId(int audioSessionId) {
        }

        @Override
        public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {
        }

        @Override
        public void enableTunnelingV21(int tunnelingAudioSessionId) {
        }

        @Override
        public void disableTunneling() {
        }

        @Override
        public void setVolume(float volume) {
        }

        @Override
        public void pause() {
        }

        @Override
        public void flush() {
            mStartUs = C.TIME_UNSET;
            mFramesSinceStart = 0;
            mEnded = false;
        }

        @Override
        public void reset() {
            flush();
        }
    }
}
