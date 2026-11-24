package com.cleanvoice.player.dsp;

/**
 * Streaming high-quality sample-rate converter (windowed-sinc, 32 taps, Blackman-Harris window),
 * interleaved multi-channel. Used to bring every file to 48 kHz for the neural denoiser.
 */
final class Resampler {
    private static final int HALF = 16;          // taps on each side
    private static final int PHASES = 512;

    private final int channels;
    private final boolean identity;
    private final double step;                   // input samples per output sample
    private final float[] table;                 // (PHASES + 1) * 2*HALF

    private float[] buf;                         // interleaved input history
    private int bufFrames;
    private double pos;                          // read position (input frames) in buf
    private float[] out = new float[0];

    Resampler(int inRate, int outRate, int channels) {
        this.channels = channels;
        this.identity = inRate == outRate;
        this.step = (double) inRate / outRate;
        double cutoff = Math.min(1.0, (double) outRate / inRate) * 0.97; // keep a little guard band
        table = new float[(PHASES + 1) * 2 * HALF];
        for (int p = 0; p <= PHASES; p++) {
            double frac = (double) p / PHASES;
            for (int k = -HALF + 1; k <= HALF; k++) {
                double d = k - frac;
                double x = Math.PI * cutoff * d;
                double sinc = Math.abs(x) < 1e-9 ? 1.0 : Math.sin(x) / x;
                double w = (d + HALF) / (2.0 * HALF);   // 0..1 over the window span
                double win = w <= 0 || w >= 1 ? 0 : 0.35875 - 0.48829 * Math.cos(2 * Math.PI * w)
                        + 0.14128 * Math.cos(4 * Math.PI * w) - 0.01168 * Math.cos(6 * Math.PI * w);
                table[p * 2 * HALF + (k + HALF - 1)] = (float) (cutoff * sinc * win);
            }
        }
        buf = new float[4096 * channels];
        bufFrames = HALF;            // zero history so the first samples have context
        pos = HALF;
    }

    /** Input frames of delay this converter adds (measured in input samples). */
    static int latencyInputFrames() { return HALF; }

    float[] output() { return out; }

    /** Converts {@code frames} interleaved frames; result is in {@link #output()}; returns frame count. */
    int process(float[] in, int frames) {
        if (identity) {
            if (out.length < frames * channels) out = new float[frames * channels];
            System.arraycopy(in, 0, out, 0, frames * channels);
            return frames;
        }
        // append
        if ((bufFrames + frames) * channels > buf.length) {
            float[] nb = new float[Math.max(buf.length * 2, (bufFrames + frames) * channels + 1024)];
            System.arraycopy(buf, 0, nb, 0, bufFrames * channels);
            buf = nb;
        }
        System.arraycopy(in, 0, buf, bufFrames * channels, frames * channels);
        bufFrames += frames;

        int maxOut = (int) ((bufFrames - pos) / step) + 2;
        if (out.length < maxOut * channels) out = new float[maxOut * channels];

        int n = 0;
        while (true) {
            int i0 = (int) pos;
            if (i0 + HALF >= bufFrames) break;
            double frac = pos - i0;
            double ph = frac * PHASES;
            int p0 = (int) ph;
            float t = (float) (ph - p0);
            int b0 = p0 * 2 * HALF, b1 = b0 + 2 * HALF;
            int start = i0 - HALF + 1;
            for (int c = 0; c < channels; c++) {
                float acc = 0f;
                int idx = start * channels + c;
                for (int k = 0; k < 2 * HALF; k++, idx += channels) {
                    float w = table[b0 + k] + t * (table[b1 + k] - table[b0 + k]);
                    acc += buf[idx] * w;
                }
                out[n * channels + c] = acc;
            }
            n++;
            pos += step;
        }
        // drop consumed input, keep 2*HALF frames of history
        int drop = (int) pos - HALF;
        if (drop > 0) {
            System.arraycopy(buf, drop * channels, buf, 0, (bufFrames - drop) * channels);
            bufFrames -= drop;
            pos -= drop;
        }
        return n;
    }

    void reset() {
        java.util.Arrays.fill(buf, 0f);
        bufFrames = HALF;
        pos = HALF;
    }
}
