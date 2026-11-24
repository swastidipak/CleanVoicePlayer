package com.cleanvoice.player.dsp;

import java.util.Arrays;

/**
 * Voice enhancement chain:
 *
 *   resample to 48 kHz
 *   -> RNNoise neural speech denoiser (removes fans, traffic, crowd, keyboard, claps, hiss, music...)
 *   -> speech gate driven by the network's voice detector (silences claps/noises between words)
 *   -> light spectral polish for leftover steady hiss/hum
 *   -> voice clarity (de-mud + presence EQ, compressor with downward expander)
 *   -> volume boost + peak limiter
 *
 * process() accepts interleaved float PCM at the input rate and returns interleaved
 * PCM at {@link #outputRate()} (48 kHz). If the native library is unavailable it falls back
 * to the spectral denoiser only, at the input rate.
 */
public final class AudioProcessor {

    private static final int FRAME = RNNoise.FRAME;

    private final int inRate;
    private final int rate;
    private final int channels;
    private final boolean neural;

    private final Resampler resampler;
    private final ClapSuppressor clap;
    private final RNNoise[] rn;
    private final SpectralDenoiser[] spectral;
    private final float[][] frameIn;      // per-channel RNNoise input frame
    private final float[][] dryFrame;     // per-channel copy (for low-strength blending)
    private final float[][] dryDelay;     // aligns dry with RNNoise output
    private final float[][] wetPrev;      // one-frame look-ahead for the gate
    private final float[][] dryPrev;
    private int fill;
    private int dryDelayPos;
    private float vadPrev;
    private boolean havePrev;
    private int hold;
    private float gateGain = 1f;

    private final Biquad[] hp, mud, presence, air;
    private float appliedClarity = -1f;
    private float compEnv;
    private float speechLevelDb = -60f;
    private final float levelFall;
    private float limGain = 1f;
    private final float compAttack, compRelease, limRelease, gateOpen, gateClose;

    private float[] out = new float[0];
    private int outFrames;

    private volatile float noiseAmount;
    private volatile float clarityAmount;
    private volatile float volumeGain = 1f;

    /** Delay (samples) of RNNoise's output relative to its input. Measured, see tools/dsptest. */
    static final int RN_DELAY = 480;

    public AudioProcessor(int inputRate, int channels) {
        this.inRate = inputRate;
        this.channels = Math.max(1, channels);
        boolean ok = RNNoise.load();
        this.neural = ok;
        this.rate = ok ? RNNoise.SAMPLE_RATE : inputRate;
        this.resampler = new Resampler(inputRate, rate, this.channels);
        this.clap = new ClapSuppressor(rate, this.channels);
        rn = new RNNoise[this.channels];
        spectral = new SpectralDenoiser[this.channels];
        frameIn = new float[this.channels][FRAME];
        dryFrame = new float[this.channels][FRAME];
        dryDelay = new float[this.channels][RN_DELAY];
        wetPrev = new float[this.channels][FRAME];
        dryPrev = new float[this.channels][FRAME];
        hp = new Biquad[this.channels];
        mud = new Biquad[this.channels];
        presence = new Biquad[this.channels];
        air = new Biquad[this.channels];
        for (int c = 0; c < this.channels; c++) {
            if (neural) rn[c] = new RNNoise();
            spectral[c] = new SpectralDenoiser(rate);
            hp[c] = new Biquad(); mud[c] = new Biquad(); presence[c] = new Biquad(); air[c] = new Biquad();
        }
        compAttack = coef(0.005f);
        compRelease = coef(0.150f);
        limRelease = coef(0.080f);
        gateOpen = coef(0.003f);
        gateClose = coef(0.060f);
        levelFall = 6f / rate; // speech level estimate falls 6 dB per second
    }

    private float coef(float seconds) { return (float) Math.exp(-1.0 / (seconds * rate)); }

    /** Output sample rate (48000 when the neural denoiser is active). */
    public int outputRate() { return rate; }
    public boolean isNeural() { return neural; }
    int clapDetections() { return clap.getDetections(); }

    /** 0..1 — background noise removal strength. */
    public void setNoiseReduction(float a) { noiseAmount = clamp(a, 0f, 1f); }
    /** 0..1 — voice clarity strength. */
    public void setVoiceClarity(float a) { clarityAmount = clamp(a, 0f, 1f); }
    /** Linear volume multiplier 1..5. */
    public void setVolumeGain(float g) { volumeGain = clamp(g, 0.1f, 5f); }

    /** Output frames by which the result lags the input. */
    public int latencyFrames() {
        int rs = (int) Math.round(Resampler.latencyInputFrames() * (double) rate / inRate);
        return neural ? rs + ClapSuppressor.LATENCY + RN_DELAY + FRAME + SpectralDenoiser.LATENCY
                      : rs + ClapSuppressor.LATENCY + SpectralDenoiser.LATENCY;
    }

    public float[] output() { return out; }

    /** Clears audio history after a seek (keeps learned noise profiles). */
    public void resetBuffers() {
        resampler.reset();
        clap.reset();
        for (int c = 0; c < channels; c++) {
            spectral[c].clearBuffers();
            Arrays.fill(dryDelay[c], 0f);
            Arrays.fill(wetPrev[c], 0f);
            Arrays.fill(dryPrev[c], 0f);
            hp[c].reset(); mud[c].reset(); presence[c].reset(); air[c].reset();
        }
        fill = 0;
        havePrev = false;
        compEnv = 0f;
        limGain = 1f;
    }

    /** Processes {@code frames} interleaved input frames. Result in {@link #output()}; returns frame count. */
    public int process(float[] in, int frames) {
        int n = resampler.process(in, frames);
        float[] rs = resampler.output();
        outFrames = 0;
        ensureOut(n + FRAME * 2);
        clap.setDepth(clamp((noiseAmount - 0.4f) / 0.6f, 0f, 1f));
        for (int f = 0; f < n; f++) clap.process(rs, f * channels);
        if (!neural) {
            for (int f = 0; f < n; f++) emit(rs, f * channels, 1f);
        } else {
            for (int f = 0; f < n; f++) {
                for (int c = 0; c < channels; c++) frameIn[c][fill] = rs[f * channels + c];
                if (++fill == FRAME) {
                    fill = 0;
                    runNeuralFrame();
                }
            }
        }
        postProcess(outFrames);
        return outFrames;
    }

    /** Pushes silence through to get the delayed tail out at end of file. */
    public int flush() {
        int total = latencyFrames() + FRAME;
        float[] z = new float[total * channels];
        int inFrames = (int) Math.ceil(total * (double) inRate / rate) + Resampler.latencyInputFrames() + 1;
        if (z.length < inFrames * channels) z = new float[inFrames * channels];
        return process(z, inFrames);
    }

    // ------------------------------------------------------------------------------------------

    private final float[] tmp = new float[FRAME];

    private void runNeuralFrame() {
        final float amt = noiseAmount;
        float vad = 0f;
        for (int c = 0; c < channels; c++) {
            // dry copy delayed to line up with RNNoise output
            float[] d = dryFrame[c], line = dryDelay[c], x = frameIn[c];
            for (int i = 0; i < FRAME; i++) {
                d[i] = line[(dryDelayPos + i) % RN_DELAY];
                line[(dryDelayPos + i) % RN_DELAY] = x[i];
            }
            if (amt > 0f) {
                System.arraycopy(x, 0, tmp, 0, FRAME);
                float v = rn[c].process(tmp);
                if (v > vad) vad = v;
                System.arraycopy(tmp, 0, x, 0, FRAME); // x now holds wet
            } else {
                // keep network state warm but output dry
                System.arraycopy(x, 0, tmp, 0, FRAME);
                float v = rn[c].process(tmp);
                if (v > vad) vad = v;
                System.arraycopy(d, 0, x, 0, FRAME);
            }
        }
        dryDelayPos = (dryDelayPos + FRAME) % RN_DELAY;

        if (!havePrev) {
            for (int c = 0; c < channels; c++) {
                System.arraycopy(frameIn[c], 0, wetPrev[c], 0, FRAME);
                System.arraycopy(dryFrame[c], 0, dryPrev[c], 0, FRAME);
            }
            vadPrev = vad;
            havePrev = true;
            // emit one frame of silence to keep latency constant
            for (int i = 0; i < FRAME; i++) for (int c = 0; c < channels; c++) out[(outFrames + i) * channels + c] = 0f;
            outFrames += FRAME;
            return;
        }

        // Gate: open if speech in this frame or the next one (look-ahead), hold 250 ms after speech.
        float v = Math.max(vadPrev, vad);
        if (v >= 0.5f) hold = 25; else if (hold > 0) hold--;
        float depth = clamp((amt - 0.5f) * 2f, 0f, 1f);
        float floor = (float) Math.pow(10, (-45f * depth) / 20f);
        float target = (v >= 0.5f || hold > 0) ? 1f : (v > 0.25f ? floor + (1 - floor) * (v - 0.25f) / 0.25f : floor);
        float mix = clamp(amt * 2f, 0f, 1f); // 0..50%: blend original -> fully cleaned

        for (int i = 0; i < FRAME; i++) {
            float a = target > gateGain ? gateOpen : gateClose;
            gateGain = a * gateGain + (1 - a) * target;
            for (int c = 0; c < channels; c++) {
                float w = wetPrev[c][i], d = dryPrev[c][i];
                float y = amt > 0f ? (w * mix + d * (1 - mix)) * gateGain : d;
                out[(outFrames + i) * channels + c] = y;
            }
        }
        outFrames += FRAME;

        for (int c = 0; c < channels; c++) {
            System.arraycopy(frameIn[c], 0, wetPrev[c], 0, FRAME);
            System.arraycopy(dryFrame[c], 0, dryPrev[c], 0, FRAME);
        }
        vadPrev = vad;
    }

    private void emit(float[] src, int off, float g) {
        for (int c = 0; c < channels; c++) out[outFrames * channels + c] = src[off + c] * g;
        outFrames++;
    }

    private void postProcess(int frames) {
        final float amt = noiseAmount;
        final float clarity = clarityAmount;
        final float gain = volumeGain;
        if (Math.abs(clarity - appliedClarity) > 1e-3f) updateClarityFilters(clarity);
        // spectral stage: polish after the network, or full denoiser in fallback mode
        float specAmt = neural ? clamp((amt - 0.5f) * 2f, 0f, 1f) * 0.5f : amt;
        for (int c = 0; c < channels; c++) {
            spectral[c].setAmount(specAmt);
            spectral[c].setUseGate(!neural);
        }

        // Dynamics follow the speaker's own level: compress loud words a little, and push anything
        // far below the voice (leftover noise, room tone) further down instead of lifting it.
        final float ratio = 1f + 2f * clarity;
        final float makeupDb = 3f * clarity;
        final float expSlope = 2.5f * clarity;

        for (int f = 0; f < frames; f++) {
            int base = f * channels;
            float peak = 0f;
            for (int c = 0; c < channels; c++) {
                float x = spectral[c].process(out[base + c]);
                if (clarity > 0f) {
                    x = hp[c].process(x);
                    x = mud[c].process(x);
                    x = presence[c].process(x);
                    x = air[c].process(x);
                }
                out[base + c] = x;
                float ax = Math.abs(x);
                if (ax > peak) peak = ax;
            }

            float dynGain = 1f;
            if (clarity > 0f) {
                float a = peak > compEnv ? compAttack : compRelease;
                compEnv = a * compEnv + (1 - a) * peak;
                float envDb = 20f * (float) Math.log10(compEnv + 1e-9f);
                speechLevelDb = Math.max(envDb, speechLevelDb - levelFall);
                float compThr = speechLevelDb - 10f, expThr = speechLevelDb - 24f;
                float gDb = makeupDb;
                if (envDb > compThr) gDb -= (envDb - compThr) * (1f - 1f / ratio);
                else if (envDb < expThr) gDb -= Math.min(30f, (expThr - envDb) * expSlope);
                dynGain = (float) Math.pow(10, gDb / 20.0);
            }

            float total = dynGain * gain;
            float outPeak = peak * total;
            final float ceiling = 0.944f;
            float target = outPeak > ceiling ? ceiling / outPeak : 1f;
            if (target < limGain) limGain = target;
            else limGain = limRelease * limGain + (1 - limRelease) * target;
            total *= limGain;
            for (int c = 0; c < channels; c++) {
                float y = out[base + c] * total;
                if (y > 1f) y = 1f; else if (y < -1f) y = -1f;
                out[base + c] = y;
            }
        }
    }

    private void updateClarityFilters(float amt) {
        appliedClarity = amt;
        for (int c = 0; c < channels; c++) {
            hp[c].setHighPass(rate, 75f, 0.707f);                       // rumble / handling noise only
            mud[c].setPeaking(rate, 300f, 1.2f, -3f * amt);            // less boxy
            presence[c].setPeaking(rate, 2800f, 1.0f, 4f * amt);       // speech intelligibility
            air[c].setHighShelf(rate, Math.min(8000f, rate * 0.4f), 1.5f * amt);
        }
    }

    private void ensureOut(int frames) {
        if (out.length < frames * channels) out = new float[frames * channels];
    }

    /** Releases native resources. */
    public void release() {
        for (RNNoise r : rn) if (r != null) r.close();
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
