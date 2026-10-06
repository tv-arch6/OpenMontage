package com.Cigram.vid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Voice notes: record, draw, play.
 *
 * Recording is AAC in an MP4 container (`audio/mp4`), which every Android 7+
 * device can write and the Worker's MIME sniffer recognises. While recording, the
 * amplitude is sampled 20x a second into a small array — that array IS the
 * waveform, so nothing has to be decoded afterwards to draw it.
 *
 * Lifecycle is strict: {@link Recorder#release()} and {@link Player#release()}
 * stop the hardware and the timers, and both are safe to call twice. The Activity
 * calls them from onStop/onDestroy, so a recorder can never be left holding the
 * microphone.
 */
public final class CigramAdsVoice {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 20 samples a second: smooth enough to look alive, small enough to send. */
    private static final long SAMPLE_EVERY_MS = 50L;
    private static final int MAX_SAMPLES = 1200; // 60 seconds
    public static final long MAX_DURATION_MS = 180000L;

    private CigramAdsVoice() { }

    // =============================================================== recording

    public interface RecorderListener {
        /** Fired ~20x a second: the elapsed time and the newest amplitude (0..1). */
        void onTick(long elapsedMs, float level);

        void onStopped(File file, long durationMs, float[] waveform);

        void onFailed(String message);
    }

    public static final class Recorder {
        private final Context app;
        private final RecorderListener listener;
        private final List<Float> samples = new ArrayList<Float>();
        private MediaRecorder recorder;
        private File file;
        private long startedAt;
        private boolean stopped;
        private boolean released;

        public Recorder(Context context, RecorderListener listener) {
            this.app = context.getApplicationContext();
            this.listener = listener;
        }

        public boolean isRecording() {
            return recorder != null && !stopped;
        }

        public long elapsedMs() {
            return startedAt == 0L ? 0L : System.currentTimeMillis() - startedAt;
        }

        /** Returns false (and reports) when the microphone is unavailable. */
        public boolean start() {
            if (recorder != null) return false;
            try {
                File dir = new File(app.getCacheDir(), "ads_voice");
                if (!dir.exists()) dir.mkdirs();
                file = new File(dir, "voice-" + System.currentTimeMillis() + ".m4a");

                MediaRecorder created = new MediaRecorder();
                created.setAudioSource(MediaRecorder.AudioSource.MIC);
                created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                created.setAudioChannels(1);
                created.setAudioSamplingRate(44100);
                created.setAudioEncodingBitRate(64000);
                created.setMaxDuration((int) MAX_DURATION_MS);
                created.setOutputFile(file.getAbsolutePath());
                created.prepare();
                created.start();
                recorder = created;
                startedAt = System.currentTimeMillis();
                MAIN.postDelayed(tick, SAMPLE_EVERY_MS);
                return true;
            } catch (Throwable error) {
                cleanup();
                fail("تعذر بدء التسجيل. تأكد من إذن الميكروفون.");
                return false;
            }
        }

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (recorder == null || stopped) return;
                float level = 0f;
                try {
                    // 32767 is the documented ceiling; the square root spreads quiet
                    // speech across the bar heights instead of flattening it.
                    int amplitude = recorder.getMaxAmplitude();
                    level = (float) Math.sqrt(Math.max(0, amplitude) / 32767f);
                    if (level > 1f) level = 1f;
                } catch (Throwable ignored) { }
                if (samples.size() < MAX_SAMPLES) samples.add(Float.valueOf(level));
                long elapsed = elapsedMs();
                if (listener != null) listener.onTick(elapsed, level);
                if (elapsed >= MAX_DURATION_MS) {
                    stop(true);
                    return;
                }
                MAIN.postDelayed(this, SAMPLE_EVERY_MS);
            }
        };

        /** {@code keep} false discards the file (slide-to-cancel). */
        public void stop(boolean keep) {
            if (stopped) return;
            stopped = true;
            MAIN.removeCallbacks(tick);
            long duration = elapsedMs();
            File recorded = file;
            try {
                if (recorder != null) {
                    recorder.stop();
                }
            } catch (Throwable ignored) {
                // A stop under ~1s throws on some devices and leaves no usable file.
                keep = false;
            } finally {
                cleanup();
            }
            if (!keep || recorded == null || !recorded.exists() || recorded.length() < 512L
                    || duration < 700L) {
                if (recorded != null) recorded.delete();
                if (listener != null && keep) fail("التسجيل قصير جداً.");
                return;
            }
            float[] waveform = new float[samples.size()];
            for (int i = 0; i < waveform.length; i++) waveform[i] = samples.get(i).floatValue();
            if (listener != null) listener.onStopped(recorded, duration, waveform);
        }

        private void cleanup() {
            try {
                if (recorder != null) recorder.reset();
            } catch (Throwable ignored) { }
            try {
                if (recorder != null) recorder.release();
            } catch (Throwable ignored) { }
            recorder = null;
        }

        /** Always safe. Discards an in-progress recording and frees the microphone. */
        public void release() {
            if (released) return;
            released = true;
            MAIN.removeCallbacks(tick);
            if (!stopped) {
                stopped = true;
                cleanup();
                if (file != null) file.delete();
            }
        }

        private void fail(final String message) {
            if (listener == null) return;
            MAIN.post(new Runnable() {
                @Override public void run() {
                    listener.onFailed(message);
                }
            });
        }
    }

    // ============================================================== playback

    public interface PlayerListener {
        void onProgress(int playingId, long positionMs, long durationMs);

        void onFinished(int playingId);
    }

    /**
     * One player for the whole screen: starting a second note stops the first, so
     * two voice notes can never talk over each other.
     */
    public static final class Player {
        private final PlayerListener listener;
        private MediaPlayer player;
        private int currentId = -1;
        private float speed = 1f;
        private boolean released;

        public Player(PlayerListener listener) {
            this.listener = listener;
        }

        public int playingId() {
            return player != null && player.isPlaying() ? currentId : -1;
        }

        public float speed() {
            return speed;
        }

        /** Cycles 1x -> 1.5x -> 2x and applies it to whatever is playing. */
        public float cycleSpeed() {
            speed = speed < 1.4f ? 1.5f : (speed < 1.9f ? 2f : 1f);
            try {
                if (player != null && player.isPlaying()) {
                    PlaybackParams params = player.getPlaybackParams();
                    params.setSpeed(speed);
                    player.setPlaybackParams(params);
                }
            } catch (Throwable ignored) {
                speed = 1f;
            }
            return speed;
        }

        public void toggle(Context context, int id, String path) {
            if (currentId == id && player != null) {
                try {
                    if (player.isPlaying()) {
                        player.pause();
                        MAIN.removeCallbacks(tick);
                    } else {
                        player.start();
                        MAIN.post(tick);
                    }
                    return;
                } catch (Throwable ignored) {
                    stop();
                }
            }
            play(context, id, path);
        }

        public void play(Context context, int id, String path) {
            stop();
            if (path == null || path.length() == 0) return;
            try {
                MediaPlayer created = new MediaPlayer();
                created.setDataSource(path);
                created.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override public void onCompletion(MediaPlayer mp) {
                        int finished = currentId;
                        stop();
                        if (listener != null) listener.onFinished(finished);
                    }
                });
                created.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                        int finished = currentId;
                        stop();
                        if (listener != null) listener.onFinished(finished);
                        return true;
                    }
                });
                created.prepare();
                player = created;
                currentId = id;
                try {
                    PlaybackParams params = created.getPlaybackParams();
                    params.setSpeed(speed);
                    created.setPlaybackParams(params);
                } catch (Throwable ignored) { }
                created.start();
                MAIN.post(tick);
            } catch (Throwable error) {
                stop();
            }
        }

        public void seekTo(float fraction) {
            try {
                if (player == null) return;
                int target = (int) (player.getDuration() * Math.max(0f, Math.min(1f, fraction)));
                player.seekTo(target);
            } catch (Throwable ignored) { }
        }

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (player == null) return;
                try {
                    if (listener != null) {
                        listener.onProgress(currentId, player.getCurrentPosition(), player.getDuration());
                    }
                    if (player.isPlaying()) MAIN.postDelayed(this, 60L);
                } catch (Throwable ignored) { }
            }
        };

        public void stop() {
            MAIN.removeCallbacks(tick);
            MediaPlayer current = player;
            player = null;
            currentId = -1;
            if (current == null) return;
            try {
                current.reset();
            } catch (Throwable ignored) { }
            try {
                current.release();
            } catch (Throwable ignored) { }
        }

        /** Called from onDestroy. Safe twice. */
        public void release() {
            if (released) return;
            released = true;
            stop();
        }
    }

    // =============================================================== waveform

    /**
     * Draws the bars of a voice note and the played portion in the accent colour.
     * It holds only the float array it is given — no decoding, no bitmaps.
     */
    public static final class Waveform extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float[] levels = new float[0];
        private float played = 0f;
        private int baseColor = 0x55FFFFFF;
        private int activeColor = CigramAdsUi.ACCENT;

        public Waveform(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        public void setLevels(float[] values) {
            levels = values == null ? new float[0] : values;
            invalidate();
        }

        public void setColors(int base, int active) {
            baseColor = base;
            activeColor = active;
            invalidate();
        }

        /** 0..1 of the note that has been played. */
        public void setPlayed(float fraction) {
            played = Math.max(0f, Math.min(1f, fraction));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;

            float density = getResources().getDisplayMetrics().density;
            float barWidth = 2f * density;
            float gap = 1.6f * density;
            int bars = Math.max(1, (int) ((width + gap) / (barWidth + gap)));
            float centre = height / 2f;
            float maxHalf = Math.max(1.5f * density, centre - density);

            for (int i = 0; i < bars; i++) {
                float level;
                if (levels.length == 0) {
                    level = 0.12f;
                } else {
                    // resample the captured array onto the bars we can actually draw
                    int from = (int) ((long) i * levels.length / bars);
                    int to = (int) ((long) (i + 1) * levels.length / bars);
                    if (to <= from) to = from + 1;
                    float peak = 0f;
                    for (int k = from; k < to && k < levels.length; k++) {
                        if (levels[k] > peak) peak = levels[k];
                    }
                    level = Math.max(0.1f, peak);
                }
                float half = Math.max(1f * density, level * maxHalf);
                float left = i * (barWidth + gap);
                boolean isPlayed = (i + 1) / (float) bars <= played;
                paint.setColor(isPlayed ? activeColor : baseColor);
                canvas.drawRoundRect(new RectF(left, centre - half, left + barWidth, centre + half),
                        barWidth / 2f, barWidth / 2f, paint);
            }
        }
    }

    /** Packs a waveform into the compact string sent with the message. */
    public static String encode(float[] levels) {
        if (levels == null || levels.length == 0) return "";
        StringBuilder sb = new StringBuilder(levels.length);
        for (float level : levels) {
            int value = Math.max(0, Math.min(35, Math.round(level * 35f)));
            sb.append(Character.forDigit(value, 36));
        }
        return sb.toString();
    }

    public static float[] decode(String packed) {
        if (packed == null || packed.length() == 0) return new float[0];
        float[] out = new float[packed.length()];
        for (int i = 0; i < packed.length(); i++) {
            int value = Character.digit(packed.charAt(i), 36);
            out[i] = value < 0 ? 0.1f : value / 35f;
        }
        return out;
    }
}
