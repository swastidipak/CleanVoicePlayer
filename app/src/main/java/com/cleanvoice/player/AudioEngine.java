package com.cleanvoice.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.cleanvoice.player.dsp.AudioProcessor;

/**
 * Offline player: decodes the file, runs every block through {@link AudioProcessor}
 * and plays it with AudioTrack. All effect settings apply live while playing.
 */
final class AudioEngine {

    interface Listener {
        void onProgress(long positionUs, long durationUs);
        void onPlayStateChanged(boolean playing);
        void onError(String message);
    }

    private final Context appContext;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private Uri uri;
    private long durationUs;
    private Thread thread;
    private volatile boolean playing;
    private volatile boolean stopRequested;
    private volatile long seekRequestUs = -1;
    private volatile long positionUs;
    private volatile AudioTrack track;
    private volatile AudioProcessor processor;

    // effect settings
    private volatile boolean enabled = true;
    private volatile float noise, clarity, volume = 1f;

    AudioEngine(Context ctx, Listener listener) {
        this.appContext = ctx.getApplicationContext();
        this.listener = listener;
    }

    /** Loads a file (stops anything playing). Returns duration in microseconds. */
    long load(Uri newUri) throws Exception {
        stop();
        try (PcmDecoder probe = new PcmDecoder(appContext, newUri)) {
            durationUs = probe.getDurationUs();
        }
        uri = newUri;
        positionUs = 0;
        return durationUs;
    }

    boolean hasFile() { return uri != null; }
    boolean isPlaying() { return playing; }
    long getDurationUs() { return durationUs; }

    void play() {
        if (uri == null) return;
        synchronized (lock) {
            playing = true;
            lock.notifyAll();
        }
        AudioTrack t = track;
        if (t != null) {
            try { t.play(); } catch (IllegalStateException ignored) { }
        }
        if (thread == null || !thread.isAlive()) {
            stopRequested = false;
            final long startAt = positionUs >= durationUs && durationUs > 0 ? 0 : positionUs;
            thread = new Thread(() -> runLoop(startAt), "CleanVoice-Player");
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        }
        main.post(() -> listener.onPlayStateChanged(true));
    }

    void pause() {
        playing = false;
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); } catch (IllegalStateException ignored) { }
        }
        main.post(() -> listener.onPlayStateChanged(false));
    }

    void stop() {
        stopRequested = true;
        playing = false;
        synchronized (lock) { lock.notifyAll(); }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try { t.join(1500); } catch (InterruptedException ignored) { }
        }
        thread = null;
        positionUs = 0;
        main.post(() -> {
            listener.onPlayStateChanged(false);
            listener.onProgress(0, durationUs);
        });
    }

    void seekTo(long us) {
        positionUs = us;
        if (thread != null && thread.isAlive()) {
            seekRequestUs = us;
            synchronized (lock) { lock.notifyAll(); }
        }
    }

    void release() {
        stop();
        uri = null;
    }

    // ---------------- settings (live) ----------------

    void setEnhancementsEnabled(boolean on) { enabled = on; applySettings(processor); }
    void setNoiseReduction(float amount) { noise = amount; applySettings(processor); }
    void setVoiceClarity(float amount) { clarity = amount; applySettings(processor); }
    void setVolume(float gain) { volume = gain; applySettings(processor); }

    private void applySettings(AudioProcessor p) {
        if (p == null) return;
        p.setNoiseReduction(enabled ? noise : 0f);
        p.setVoiceClarity(enabled ? clarity : 0f);
        p.setVolumeGain(enabled ? volume : 1f);
    }

    // ---------------- playback thread ----------------

    private void runLoop(long startAtUs) {
        AudioTrack t = null;
        try (PcmDecoder dec = new PcmDecoder(appContext, uri)) {
            if (startAtUs > 0) dec.seekTo(startAtUs);
            int formatVersion = -1;
            int channels = 0;
            long framesSinceFlush = 0;
            long lastUiUpdate = 0;

            while (!stopRequested) {
                // Pause handling
                synchronized (lock) {
                    while (!playing && !stopRequested && seekRequestUs < 0) lock.wait();
                }
                if (stopRequested) break;

                long seek = seekRequestUs;
                if (seek >= 0) {
                    seekRequestUs = -1;
                    dec.seekTo(seek);
                    if (t != null) {
                        t.pause();
                        t.flush();
                        if (playing) t.play();
                    }
                    if (processor != null) processor.resetBuffers();
                    framesSinceFlush = 0;
                    positionUs = seek;
                    postProgress();
                    continue;
                }

                int frames = dec.read();
                if (frames < 0) {
                    // End of file: push the processor's internal delay out, let it finish playing.
                    if (t != null && processor != null) {
                        int tail = processor.flush();
                        writeAll(t, processor.output(), tail * channels);
                        framesSinceFlush += tail;
                        long deadline = SystemClock.uptimeMillis() + 3000;
                        while (!stopRequested && playing && SystemClock.uptimeMillis() < deadline
                                && (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) < framesSinceFlush) {
                            Thread.sleep(20);
                        }
                    }
                    positionUs = 0;
                    playing = false;
                    main.post(() -> {
                        listener.onPlayStateChanged(false);
                        listener.onProgress(0, durationUs);
                    });
                    break;
                }

                if (dec.getFormatVersion() != formatVersion || t == null) {
                    formatVersion = dec.getFormatVersion();
                    if (t != null) { t.release(); }
                    if (processor != null) processor.release();
                    channels = dec.getChannels();
                    AudioProcessor p = new AudioProcessor(dec.getSampleRate(), channels);
                    applySettings(p);
                    processor = p;
                    t = createTrack(p.outputRate(), channels);
                    track = t;
                    framesSinceFlush = 0;
                    if (playing) t.play();
                }

                int outFrames = processor.process(dec.getBuffer(), frames);
                if (!writeAll(t, processor.output(), outFrames * channels)) continue; // interrupted by seek/stop
                framesSinceFlush += outFrames;

                positionUs = dec.getLastPtsUs();
                long now = SystemClock.uptimeMillis();
                if (now - lastUiUpdate > 200) {
                    lastUiUpdate = now;
                    postProgress();
                }
            }
        } catch (InterruptedException ignored) {
            // stopping
        } catch (Exception e) {
            playing = false;
            final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            main.post(() -> {
                listener.onPlayStateChanged(false);
                listener.onError(msg);
            });
        } finally {
            track = null;
            AudioProcessor p = processor;
            processor = null;
            if (p != null) p.release();
            if (t != null) {
                try { t.stop(); } catch (Exception ignored) { }
                t.release();
            }
        }
    }

    /** Non-blocking write loop so pause/seek/stop react immediately. */
    private boolean writeAll(AudioTrack t, float[] data, int len) throws InterruptedException {
        int off = 0;
        while (off < len) {
            if (stopRequested || seekRequestUs >= 0) return false;
            if (!playing) {
                synchronized (lock) {
                    while (!playing && !stopRequested && seekRequestUs < 0) lock.wait();
                }
                continue;
            }
            int w = t.write(data, off, len - off, AudioTrack.WRITE_NON_BLOCKING);
            if (w < 0) throw new IllegalStateException("Audio output error " + w);
            if (w == 0) Thread.sleep(5);
            off += w;
        }
        return true;
    }

    private static AudioTrack createTrack(int sampleRate, int channels) {
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT);
        int bufSize = Math.max(minBuf * 2, sampleRate * channels * 4 / 5); // ~200 ms
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    private void postProgress() {
        final long pos = positionUs;
        main.post(() -> listener.onProgress(pos, durationUs));
    }
}
