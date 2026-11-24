package com.cleanvoice.player.dsp;

/**
 * Removes impulsive non-voice sounds — claps, knocks, table taps, door bangs, clicks.
 * Detects a sudden, broadband, noise-like burst (unlike the harmonic onset of a spoken word)
 * and ducks it for its duration. Works on interleaved audio with a short look-ahead so the
 * attack of the clap is caught too. Fixed latency {@link #LATENCY} samples.
 */
final class ClapSuppressor {
    private static final int BLOCK = 96;                 // 2 ms @ 48 kHz
    static final int LATENCY = BLOCK * 3;                // look-ahead
    private static final int HIST = 16;                  // 32 ms background history
    private static final int FFT_N = 256;

    private final int channels;
    private final float[] delay;                         // interleaved delay line
    private int dpos;
    private final float[] mono = new float[FFT_N];       // recent mono samples for flatness
    private int mpos;

    private final Biquad midHp = new Biquad(), hiHp = new Biquad();
    private float accMid, accHi, accAll;
    private int blockFill;
    private final float[] histMid = new float[HIST], histHi = new float[HIST], histAll = new float[HIST];
    private int hpos;
    private int warm;
    private float level;
    private static final float LEVEL_DECAY = (float) Math.pow(0.5, 1.0 / 1500);

    private boolean active;
    private int activeBlocks;
    private float bgAllAtOnset;
    private float gain = 1f;
    private float floor = 1f;
    private int detections;

    private final FFT fft = new FFT(FFT_N);
    private final float[] re = new float[FFT_N], im = new float[FFT_N], win = new float[FFT_N];
    private final float ramp;

    ClapSuppressor(int sampleRate, int channels) {
        this.channels = channels;
        delay = new float[LATENCY * channels];
        midHp.setHighPass(sampleRate, 300f, 0.707f);
        hiHp.setHighPass(sampleRate, 3000f, 0.707f);
        for (int i = 0; i < FFT_N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / FFT_N));
        ramp = (float) Math.exp(-1.0 / (0.001 * sampleRate));
    }

    /** 0 = off, 1 = duck claps by 40 dB. */
    void setDepth(float d) { floor = (float) Math.pow(10, -40f * Math.max(0f, Math.min(1f, d)) / 20f); }

    int getDetections() { return detections; }

    /** Processes one interleaved frame in place (frame at {@code off}). */
    void process(float[] buf, int off) {
        float m = 0f;
        for (int c = 0; c < channels; c++) m += buf[off + c];
        m /= channels;

        mono[mpos] = m;
        mpos = (mpos + 1) % FFT_N;
        float lowCut = midHp.process(m);
        float hi = hiHp.process(lowCut);
        float mid = lowCut - hi; // ~300-3000 Hz
        accMid += mid * mid;
        accHi += hi * hi;
        accAll += m * m;
        if (++blockFill == BLOCK) endBlock();

        float target = active ? floor : 1f;
        gain = target < gain ? target + (gain - target) * ramp * 0.5f : target + (gain - target) * ramp;

        int base = dpos * channels;
        for (int c = 0; c < channels; c++) {
            float delayed = delay[base + c];
            delay[base + c] = buf[off + c];
            buf[off + c] = delayed * gain;
        }
        dpos = (dpos + 1) % LATENCY;
    }

    private void endBlock() {
        float eMid = accMid / BLOCK, eHi = accHi / BLOCK, eAll = accAll / BLOCK;
        accMid = accHi = accAll = 0f;
        blockFill = 0;

        // background = average of blocks 3..HIST-1 ago (skip the most recent two)
        float bgMid = 0, bgHi = 0, bgAll = 0;
        for (int k = 2; k < HIST; k++) {
            int i = (hpos - k + HIST * 2) % HIST;
            bgMid += histMid[i]; bgHi += histHi[i]; bgAll += histAll[i];
        }
        bgMid /= (HIST - 2); bgHi /= (HIST - 2); bgAll /= (HIST - 2);

        // loudest recent sound (3 s half-life): claps worth removing are near this level,
        // quiet word onsets are not
        level = Math.max(eAll, level * LEVEL_DECAY);

        if (warm < HIST) {
            warm++;
        } else if (!active) {
            boolean loud = eAll > 1e-5f && eAll > 0.25f * level;           // > -50 dBFS and within 6 dB of peak level
            boolean jump = eHi > 25f * bgHi + 1e-9f && eMid > 6f * bgMid + 1e-9f;
            boolean hiHeavy = eHi > 0.25f * eMid;                         // claps carry lots of treble
            if (loud && jump && hiHeavy && flatness() > 0.33f) {
                active = true;
                activeBlocks = 0;
                bgAllAtOnset = bgAll;
                detections++;
            }
        } else {
            activeBlocks++;
            boolean decayed = eAll < 4f * bgAllAtOnset + 1e-8f;
            if ((decayed && activeBlocks >= 4) || activeBlocks > 60) active = false;
        }

        // while a clap is active, don't let it pollute the background estimate
        if (!active) {
            histMid[hpos] = eMid; histHi[hpos] = eHi; histAll[hpos] = eAll;
            hpos = (hpos + 1) % HIST;
        }
    }

    /** Spectral flatness (0..1) of the last 256 samples, 400 Hz - 12 kHz at 48 kHz. */
    private float flatness() {
        for (int i = 0; i < FFT_N; i++) {
            re[i] = mono[(mpos + i) % FFT_N] * win[i];
            im[i] = 0f;
        }
        fft.transform(re, im, false);
        double logSum = 0, sum = 0;
        int n = 0;
        for (int k = 2; k <= 64; k++) {
            double p = re[k] * re[k] + im[k] * im[k] + 1e-12;
            logSum += Math.log(p);
            sum += p;
            n++;
        }
        return (float) (Math.exp(logSum / n) / (sum / n));
    }

    void reset() {
        java.util.Arrays.fill(delay, 0f);
        active = false;
        gain = 1f;
        warm = 0;
        level = 0f;
    }
}
