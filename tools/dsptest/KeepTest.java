package com.cleanvoice.player.dsp;

/** Writes foreground/background stems and processed outputs for keep-all-sounds analysis. */
public class KeepTest {
    public static void main(String[] a) throws Exception {
        String dir = a[0], out = a[1], bgName = a[2];
        float[] sp = VoiceTest.readWav(dir + "/clnsp1.wav");
        float[] sp2 = VoiceTest.readWav(dir + "/clnsp8.wav");
        float[] clap = VoiceTest.readWav(dir + "/clapping.wav");
        float[] steps = VoiceTest.readWav(dir + "/footsteps.wav");
        float[] bg = VoiceTest.readWav(dir + "/" + bgName + ".wav");
        int sr = 16000, n = sr * 22;
        float[] voice = new float[n], other = new float[n], noise = new float[n];
        for (int i = 0; i < sp.length && i + sr < n; i++) voice[i + sr] += sp[i];
        for (int i = 0; i < sp2.length && i + sr * 12 < n; i++) voice[i + sr * 12] += 0.8f * sp2[i];
        float vp = VoiceTest.peak(voice);
        for (int i = 0; i < n; i++) voice[i] *= 0.4f / vp;
        for (int i = 0; i < n; i++) other[i] = 0.35f * clap[i % clap.length] + 0.25f * steps[(i + sr * 3) % steps.length];
        double g = Math.sqrt((VoiceTest.pow(voice) + VoiceTest.pow(other)) / VoiceTest.pow(bg.length >= n ? bg : tile(bg, n)) / Math.pow(10, 5 / 10.0));
        float[] bgt = tile(bg, n);
        float[] noisy = new float[n];
        for (int i = 0; i < n; i++) { noise[i] = (float) (g * bgt[i]); noisy[i] = voice[i] + other[i] + noise[i]; }
        VoiceTest.SR = sr;
        VoiceTest.writeWav(out + "/voice48.wav", VoiceTest.resampleRef(voice), 48000);
        VoiceTest.writeWav(out + "/other48.wav", VoiceTest.resampleRef(other), 48000);
        VoiceTest.writeWav(out + "/noise48.wav", VoiceTest.resampleRef(noise), 48000);
        VoiceTest.writeWav(out + "/in48.wav", VoiceTest.resampleRef(noisy), 48000);
        VoiceTest.writeWav(out + "/keep.wav", run(noisy, true, 0.9f, 0f), 48000);
        VoiceTest.writeWav(out + "/keep_lift.wav", run(noisy, true, 0.9f, 0.5f), 48000);
        VoiceTest.writeWav(out + "/voiceonly.wav", run(noisy, false, 0.9f, 0f), 48000);
    }
    static float[] tile(float[] x, int n) { float[] r = new float[n]; for (int i = 0; i < n; i++) r[i] = x[i % x.length]; return r; }
    static float[] run(float[] in, boolean keep, float nr, float clar) {
        AudioProcessor p = new AudioProcessor(16000, 1);
        p.setKeepAllSounds(keep); p.setNoiseReduction(nr); p.setVoiceClarity(clar);
        VoiceTest.FloatArr acc = new VoiceTest.FloatArr();
        for (int off = 0; off < in.length; off += 4096) { int len = Math.min(4096, in.length - off); float[] x = new float[len]; System.arraycopy(in, off, x, 0, len); int m = p.process(x, len); acc.add(p.output(), m); }
        int m = p.flush(); acc.add(p.output(), m);
        int lat = p.latencyFrames(); float[] r = new float[acc.n - lat]; System.arraycopy(acc.a, lat, r, 0, r.length); p.release(); return r;
    }
}
