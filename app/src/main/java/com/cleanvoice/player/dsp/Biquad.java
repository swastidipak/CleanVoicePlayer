package com.cleanvoice.player.dsp;

/** RBJ cookbook biquad filter (Direct Form I). */
final class Biquad {
    private float b0 = 1, b1, b2, a1, a2;
    private float x1, x2, y1, y2;

    void setPeaking(float sampleRate, float freq, float q, float gainDb) {
        double A = Math.pow(10, gainDb / 40.0);
        double w = 2 * Math.PI * freq / sampleRate;
        double alpha = Math.sin(w) / (2 * q);
        double cw = Math.cos(w);
        double a0 = 1 + alpha / A;
        set((1 + alpha * A) / a0, (-2 * cw) / a0, (1 - alpha * A) / a0,
                (-2 * cw) / a0, (1 - alpha / A) / a0);
    }

    void setHighPass(float sampleRate, float freq, float q) {
        double w = 2 * Math.PI * freq / sampleRate;
        double alpha = Math.sin(w) / (2 * q);
        double cw = Math.cos(w);
        double a0 = 1 + alpha;
        set(((1 + cw) / 2) / a0, (-(1 + cw)) / a0, ((1 + cw) / 2) / a0,
                (-2 * cw) / a0, (1 - alpha) / a0);
    }

    void setHighShelf(float sampleRate, float freq, float gainDb) {
        double A = Math.pow(10, gainDb / 40.0);
        double w = 2 * Math.PI * freq / sampleRate;
        double cw = Math.cos(w);
        double alpha = Math.sin(w) / 2 * Math.sqrt(2); // shelf slope S = 1
        double sq = 2 * Math.sqrt(A) * alpha;
        double a0 = (A + 1) - (A - 1) * cw + sq;
        set(A * ((A + 1) + (A - 1) * cw + sq) / a0,
                -2 * A * ((A - 1) + (A + 1) * cw) / a0,
                A * ((A + 1) + (A - 1) * cw - sq) / a0,
                2 * ((A - 1) - (A + 1) * cw) / a0,
                ((A + 1) - (A - 1) * cw - sq) / a0);
    }

    private void set(double nb0, double nb1, double nb2, double na1, double na2) {
        b0 = (float) nb0; b1 = (float) nb1; b2 = (float) nb2; a1 = (float) na1; a2 = (float) na2;
    }

    float process(float x) {
        float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1; x1 = x;
        y2 = y1; y1 = y;
        if (Math.abs(y) < 1e-20f) y = 0f; // avoid denormals
        y1 = y;
        return y;
    }

    void reset() { x1 = x2 = y1 = y2 = 0; }
}
