package com.cleanvoice.player.dsp;

import java.util.Arrays;

/**
 * Single-channel STFT spectral noise suppressor with automatic noise tracking.
 * Used as a light second stage after the neural denoiser to remove leftover steady hiss/hum,
 * and as the fallback denoiser if the native library can't load.
 * Transparent (bit-exact delayed copy) when amount == 0. Fixed latency of {@link #LATENCY} samples.
 */
final class SpectralDenoiser {
    static final int FFT_SIZE = 1024;
    static final int LATENCY = FFT_SIZE;
    private static final int HOP = FFT_SIZE / 4;
    private static final int BINS = FFT_SIZE / 2 + 1;
    private static final float OLA_SCALE = 1f / (1.5f * FFT_SIZE);

    private final int sampleRate;
    private final FFT fft = new FFT(FFT_SIZE);
    private final float[] window = new float[FFT_SIZE];
    private final float[] re = new float[FFT_SIZE];
    private final float[] im = new float[FFT_SIZE];
    private final float[] gainTmp = new float[BINS];

    private final float[] inFifo = new float[FFT_SIZE];
    private final float[] outFifo = new float[FFT_SIZE];
    private final float[] accum = new float[FFT_SIZE + HOP];
    private int rover = FFT_SIZE - HOP;

    private final float[] smoothPow = new float[BINS];
    private final float[] noise = new float[BINS];
    private final float[] prevGain = new float[BINS];
    private long frameCount;
    private float gate = 1f;
    private int speechRun;

    private volatile float amount;
    private volatile boolean useGate = true;

    SpectralDenoiser(int sampleRate) {
        this.sampleRate = sampleRate;
        for (int i = 0; i < FFT_SIZE; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / FFT_SIZE));
        Arrays.fill(prevGain, 1f);
    }

    void setAmount(float a) { amount = a; }
    void setUseGate(boolean g) { useGate = g; }

    void clearBuffers() {
        Arrays.fill(inFifo, 0f);
        Arrays.fill(outFifo, 0f);
        Arrays.fill(accum, 0f);
        rover = FFT_SIZE - HOP;
    }

    float process(float x) {
        final int latency = FFT_SIZE - HOP;
        inFifo[rover] = x;
        float out = outFifo[rover - latency];
        rover++;
        if (rover >= FFT_SIZE) {
            rover = latency;
            processFrame();
            System.arraycopy(accum, 0, outFifo, 0, HOP);
            System.arraycopy(accum, HOP, accum, 0, FFT_SIZE);
            Arrays.fill(accum, FFT_SIZE, FFT_SIZE + HOP, 0f);
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

        final float amt = amount;
        final float binHz = (float) sampleRate / FFT_SIZE;
        final float framesPerSec = (float) sampleRate / HOP;
        final float riseSlow = (float) Math.pow(10, 0.4 / framesPerSec);
        final float riseFast = (float) Math.pow(10, 1.2 / framesPerSec);
        final boolean warmup = frameCount < (long) (framesPerSec * 0.25f);

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
            if (frameCount == 0) noise[k] = p;
            else if (warmup) noise[k] = 0.85f * noise[k] + 0.15f * p;
            else if (sp < noise[k]) noise[k] = 0.9f * noise[k] + 0.1f * sp;
            else if (!speech && p < 4f * noise[k]) noise[k] = 0.97f * noise[k] + 0.03f * p;
            else noise[k] *= rise;
            if (noise[k] < 1e-14f) noise[k] = 1e-14f;
        }
        frameCount++;

        float gateTarget = speech ? 1f : (frameSnr < 1.6f ? 0f : (frameSnr - 1.6f) / 0.9f);
        gate = gateTarget > gate ? 0.5f * gate + 0.5f * gateTarget : 0.93f * gate + 0.07f * gateTarget;

        if (amt <= 0f) {
            Arrays.fill(prevGain, 1f);
        } else {
            final float overSub = 1.5f + 3.5f * amt;
            final float floor = (float) Math.pow(10, (-35f * amt) / 20f);
            final float gateMix = useGate ? Math.max(0f, (amt - 0.4f) / 0.6f) : 0f;
            final float frameGain = 1f - gateMix * (1f - gate);
            for (int k = 0; k < BINS; k++) {
                float p = re[k] * re[k] + im[k] * im[k];
                float n = noise[k] * overSub;
                float g = p > 0 ? 1f - n / p : 0f;
                g = g <= 0f ? 0f : (float) Math.sqrt(g);
                float hz = k * binHz;
                if (hz < 80f) g *= (1f - amt);
                else if (hz > 12000f) g *= (1f - 0.6f * amt);
                gainTmp[k] = g;
            }
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
        for (int i = 0; i < FFT_SIZE; i++) accum[i] += re[i] * window[i] * OLA_SCALE;
    }
}
