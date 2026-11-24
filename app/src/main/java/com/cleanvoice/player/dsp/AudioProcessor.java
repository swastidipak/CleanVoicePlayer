package com.cleanvoice.player.dsp;

/**
 * Real-time voice enhancement chain, pure Java (no Android dependencies):
 *
 *   1. Background noise removal  - STFT spectral noise suppression with automatic
 *                                  noise-profile tracking (hiss, fan, AC, traffic, hum, rumble).
 *   2. Voice clarity             - de-mud + presence EQ and a gentle voice compressor.
 *   3. Volume boost              - up to 5x (+14 dB) gain followed by a peak limiter so
 *                                  loud parts never crackle/clip.
 *
 * Settings may be changed from any thread while audio is running.
 * process() works on interleaved float samples in the range -1..1, in place.
 */
public final class AudioProcessor {

    private static final int FFT_SIZE = 1024;
    private static final int HOP = FFT_SIZE / 4;         // 75% overlap
    private static final int BINS = FFT_SIZE / 2 + 1;
    private static final float OLA_SCALE = 1f / (1.5f * FFT_SIZE); // Hann^2 @75% overlap sums to 1.5

    private final int sampleRate;
    private final int channels;
    private final FFT fft = new FFT(FFT_SIZE);
    private final float[] window = new float[FFT_SIZE];
    private final float[] re = new float[FFT_SIZE];
    private final float[] im = new float[FFT_SIZE];
    private final float[] gainTmp = new float[BINS];
    private final ChannelState[] state;

    // ---- user settings (0..1 amounts, linear gain) ----
    private volatile float noiseAmount = 0f;
    private volatile float clarityAmount = 0f;
    private volatile float volumeGain = 1f;

    // ---- clarity / dynamics state ----
    private float appliedClarity = -1f;
    private float compEnv = 0f;
    private float limGain = 1f;
    private final float compAttack, compRelease, limRelease;

    public AudioProcessor(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = Math.max(1, channels);
        for (int i = 0; i < FFT_SIZE; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / FFT_SIZE));
        }
        state = new ChannelState[this.channels];
        for (int c = 0; c < this.channels; c++) state[c] = new ChannelState();
        compAttack = coef(0.005f);
        compRelease = coef(0.150f);
        limRelease = coef(0.080f);
    }

    private float coef(float seconds) {
        return (float) Math.exp(-1.0 / (seconds * sampleRate));
    }

    /** 0..1 — how aggressively background noise is removed (1 = maximum). */
    public void setNoiseReduction(float amount) { noiseAmount = clamp(amount, 0f, 1f); }

    /** 0..1 — voice clarity enhancement strength. */
    public void setVoiceClarity(float amount) { clarityAmount = clamp(amount, 0f, 1f); }

    /** Linear volume multiplier, 1..5 (1 = original, 5 = +14 dB). */
    public void setVolumeGain(float gain) { volumeGain = clamp(gain, 0.1f, 5f); }

    /** Samples (per channel) that output lags behind input. */
    public static int latencyFrames() { return FFT_SIZE; }

    /** Clears filter history (call after seeking). Keeps the learned noise profile. */
    public void resetBuffers() {
        for (ChannelState s : state) s.clearBuffers();
        compEnv = 0f;
        limGain = 1f;
    }

    /** Processes {@code frames} interleaved frames of {@code buf}, in place. */
    public void process(float[] buf, int frames) {
        final float clarity = clarityAmount;
        if (Math.abs(clarity - appliedClarity) > 1e-3f) updateClarityFilters(clarity);
        final float gain = volumeGain;

        // Compressor settings derived from clarity amount
        final float thresholdDb = -26f;
        final float ratio = 1f + 3f * clarity;            // up to 4:1
        final float makeupDb = 7f * clarity;              // bring quiet words up

        for (int f = 0; f < frames; f++) {
            int base = f * channels;
            float peak = 0f;
            for (int c = 0; c < channels; c++) {
                ChannelState s = state[c];
                float x = s.denoise(buf[base + c]);
                if (clarity > 0f) {
                    x = s.hp.process(x);
                    x = s.mud.process(x);
                    x = s.presence.process(x);
                    x = s.air.process(x);
                }
                buf[base + c] = x;
                float ax = Math.abs(x);
                if (ax > peak) peak = ax;
            }

            // Voice compressor (stereo-linked)
            float compGain = 1f;
            if (clarity > 0f) {
                float a = peak > compEnv ? compAttack : compRelease;
                compEnv = a * compEnv + (1 - a) * peak;
                float envDb = 20f * (float) Math.log10(compEnv + 1e-9f);
                float overDb = envDb - thresholdDb;
                float grDb = overDb > 0 ? overDb * (1f - 1f / ratio) : 0f;
                compGain = dbToLin(makeupDb - grDb);
            }

            // Volume boost + brick-wall limiter (ceiling -0.5 dBFS)
            float total = compGain * gain;
            float outPeak = peak * total;
            final float ceiling = 0.944f;
            float target = outPeak > ceiling ? ceiling / outPeak : 1f;
            if (target < limGain) limGain = target;                     // instant attack
            else limGain = limRelease * limGain + (1 - limRelease) * target; // smooth release
            total *= limGain;

            for (int c = 0; c < channels; c++) {
                float y = buf[base + c] * total;
                if (y > 1f) y = 1f; else if (y < -1f) y = -1f;
                buf[base + c] = y;
            }
        }
    }

    private void updateClarityFilters(float amt) {
        appliedClarity = amt;
        for (ChannelState s : state) {
            s.hp.setHighPass(sampleRate, 70f + 50f * amt, 0.707f);          // remove rumble/plosives
            s.mud.setPeaking(sampleRate, 280f, 1.0f, -5f * amt);           // remove boxy "mud"
            s.presence.setPeaking(sampleRate, 3000f, 0.9f, 7f * amt);      // speech intelligibility
            s.air.setHighShelf(sampleRate, Math.min(7000f, sampleRate * 0.4f), 2.5f * amt);
        }
    }

    private static float dbToLin(float db) { return (float) Math.pow(10, db / 20.0); }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    // ------------------------------------------------------------------------------------------

    private final class ChannelState {
        final float[] inFifo = new float[FFT_SIZE];
        final float[] outFifo = new float[FFT_SIZE];
        final float[] accum = new float[FFT_SIZE + HOP];
        int rover = FFT_SIZE - HOP;

        final float[] smoothPow = new float[BINS];
        final float[] noise = new float[BINS];
        final float[] prevGain = new float[BINS];
        long frameCount = 0;
        float gate = 1f;
        int speechRun = 0;

        final Biquad hp = new Biquad();
        final Biquad mud = new Biquad();
        final Biquad presence = new Biquad();
        final Biquad air = new Biquad();

        ChannelState() {
            java.util.Arrays.fill(prevGain, 1f);
        }

        void clearBuffers() {
            java.util.Arrays.fill(inFifo, 0f);
            java.util.Arrays.fill(outFifo, 0f);
            java.util.Arrays.fill(accum, 0f);
            rover = FFT_SIZE - HOP;
            hp.reset(); mud.reset(); presence.reset(); air.reset();
        }

        /** Streaming STFT: push one sample, get one (delayed) denoised sample back. */
        float denoise(float x) {
            final int latency = FFT_SIZE - HOP;
            inFifo[rover] = x;
            float out = outFifo[rover - latency];
            rover++;
            if (rover >= FFT_SIZE) {
                rover = latency;
                processFrame();
                System.arraycopy(accum, 0, outFifo, 0, HOP);
                System.arraycopy(accum, HOP, accum, 0, FFT_SIZE);
                java.util.Arrays.fill(accum, FFT_SIZE, FFT_SIZE + HOP, 0f);
                System.arraycopy(inFifo, HOP, inFifo, 0, latency);
            }
            return out;
        }

        private void processFrame() {
            for (int i = 0; i < FFT_SIZE; i++) {
                re[i] = inFifo[i] * window[i];
                im[i] = 0f;
            }
            fft.transform(re, im, false);

            final float amt = noiseAmount;
            final float binHz = (float) sampleRate / FFT_SIZE;
            // Noise tracker rises ~3 dB/s so it adapts to changing backgrounds,
            // and drops instantly to any quieter level (minimum statistics).
            final float framesPerSec = (float) sampleRate / HOP;
            // Rise 4 dB/s normally; 12 dB/s if "speech" never pauses for 1.5 s (= background changed).
            final float riseSlow = (float) Math.pow(10, 0.4 / framesPerSec);
            final float riseFast = (float) Math.pow(10, 1.2 / framesPerSec);
            final boolean warmup = frameCount < (long) (framesPerSec * 0.25f);

            // Frame-level speech presence (voice band 250-4000 Hz)
            int kLo = Math.max(1, (int) (250f / binHz)), kHi = Math.min(BINS - 1, (int) (4000f / binHz));
            float sigSum = 0f, noiseSum = 0f;
            for (int k = kLo; k <= kHi; k++) {
                sigSum += re[k] * re[k] + im[k] * im[k];
                noiseSum += noise[k];
            }
            float frameSnr = frameCount == 0 ? 1f : sigSum / (noiseSum + 1e-12f);
            boolean speech = frameSnr > 2.5f;
            speechRun = speech ? speechRun + 1 : 0;
            final float rise = speechRun > framesPerSec * 1.5f ? riseFast : riseSlow;

            for (int k = 0; k < BINS; k++) {
                float p = re[k] * re[k] + im[k] * im[k];
                float sp = frameCount == 0 ? p : 0.8f * smoothPow[k] + 0.2f * p;
                smoothPow[k] = sp;
                if (frameCount == 0) {
                    noise[k] = p;
                } else if (warmup) {
                    noise[k] = 0.85f * noise[k] + 0.15f * p;          // learn quickly at start
                } else if (sp < noise[k]) {
                    noise[k] = 0.9f * noise[k] + 0.1f * sp;           // background got quieter
                } else if (!speech && p < 4f * noise[k]) {
                    noise[k] = 0.97f * noise[k] + 0.03f * p;          // average during pauses
                } else {
                    noise[k] *= rise;                                  // slow drift up
                }
                if (noise[k] < 1e-12f) noise[k] = 1e-12f;
            }
            frameCount++;

            // Gate: in pure-background frames push everything down to the floor.
            float gateTarget = speech ? 1f : (frameSnr < 1.6f ? 0f : (frameSnr - 1.6f) / 0.9f);
            gate = gateTarget > gate ? 0.5f * gate + 0.5f * gateTarget   // open fast (~3 ms)
                                     : 0.93f * gate + 0.07f * gateTarget; // close slowly (~80 ms)

            if (amt <= 0f) {
                java.util.Arrays.fill(prevGain, 1f);
            } else {
                // Strength mapping: over-subtraction 1.5..5, residual floor 0dB..-35dB
                final float overSub = 1.5f + 3.5f * amt;
                final float floor = (float) Math.pow(10, (-35f * amt) / 20f);
                final float bias = 1.0f;
                // Only the strongest settings use the gate, so low settings stay natural.
                final float gateMix = Math.max(0f, (amt - 0.4f) / 0.6f);
                final float frameGain = 1f - gateMix * (1f - gate);
                for (int k = 0; k < BINS; k++) {
                    float p = re[k] * re[k] + im[k] * im[k];
                    float n = noise[k] * bias * overSub;
                    float g = p > 0 ? 1f - n / p : 0f;
                    g = g <= 0f ? 0f : (float) Math.sqrt(g);
                    float hz = k * binHz;
                    if (hz < 80f) g *= (1f - amt);          // rumble & mains hum
                    else if (hz > 12000f) g *= (1f - 0.6f * amt); // hiss region, voice has little energy
                    gainTmp[k] = g;
                }
                // Smooth across frequency and time to avoid "musical noise" artefacts
                for (int k = 0; k < BINS; k++) {
                    float a = gainTmp[Math.max(0, k - 1)];
                    float b = gainTmp[k];
                    float c = gainTmp[Math.min(BINS - 1, k + 1)];
                    float g = 0.25f * a + 0.5f * b + 0.25f * c;
                    float prev = prevGain[k];
                    g = g > prev ? 0.55f * g + 0.45f * prev : 0.35f * g + 0.65f * prev;
                    prevGain[k] = g;
                    g *= frameGain;
                    if (g < floor) g = floor;
                    re[k] *= g;
                    im[k] *= g;
                    if (k > 0 && k < FFT_SIZE / 2) {
                        re[FFT_SIZE - k] = re[k];
                        im[FFT_SIZE - k] = -im[k];
                    }
                }
            }

            fft.transform(re, im, true);
            for (int i = 0; i < FFT_SIZE; i++) {
                accum[i] += re[i] * window[i] * OLA_SCALE;
            }
        }
    }
}
