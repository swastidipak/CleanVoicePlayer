package com.cleanvoice.player.dsp;

import java.io.File;
import java.util.Random;

/**
 * Real-recording test: real human speech (MS-SNSD) mixed with real noises at 5 dB SNR.
 * Usage: java -Djava.library.path=. -cp out com.cleanvoice.player.dsp.RealTest tools/dsptest/real [outdir]
 */
public class RealTest {
    public static void main(String[] a) throws Exception {
        String dir = a[0];
        String outDir = a.length > 1 ? a[1] : null;
        String[] speechFiles = {"clnsp1", "clnsp2", "clnsp3", "clnsp5", "clnsp8", "clnsp13"};
        String[] noiseFiles = {"Babble_1", "Babble_2", "Neighbor_1", "AirportAnnouncements_1", "AirConditioner_1",
                "VacuumCleaner_1", "Typing_1", "Munching_1", "ShuttingDoor_1", "CLAPS"};
        double snrDb = Double.parseDouble(System.getProperty("snr", "5"));
        System.out.printf("Real speech + real noise at %.0f dB SNR. Neural: %s%n", snrDb, new AudioProcessor(16000, 1).isNeural());
        System.out.printf("%-24s %16s %22s %22s%n", "noise", "NR 0% SI-SNR", "NR 100% SI-SNR / removed", "NR 80% SI-SNR / removed");
        double[] tot = new double[3];
        int cnt = 0;
        for (int k = 0; k < noiseFiles.length; k++) {
            // two different speakers per noise, 0.8 s gaps
            float[] s1 = VoiceTest.readWav(dir + "/" + speechFiles[k % 6] + ".wav");
            float[] s2 = VoiceTest.readWav(dir + "/" + speechFiles[(k + 3) % 6] + ".wav");
            int sr = VoiceTest.SR, gap = (int) (0.8 * sr);
            float[] clean = new float[gap + s1.length + gap + s2.length + gap];
            System.arraycopy(s1, 0, clean, gap, s1.length);
            System.arraycopy(s2, 0, clean, 2 * gap + s1.length, s2.length);
            float pk = VoiceTest.peak(clean);
            for (int i = 0; i < clean.length; i++) clean[i] *= 0.4f / pk;
            float[] noise = new float[clean.length];
            if (noiseFiles[k].equals("CLAPS")) {
                Random r = new Random(3);
                for (int i = (int) (0.3 * sr); i < noise.length; i += (int) ((0.45 + 0.5 * r.nextDouble()) * sr)) {
                    float amp = 0.3f + 0.3f * r.nextFloat();
                    for (int j = 0; j < sr * 0.06 && i + j < noise.length; j++)
                        noise[i + j] += (float) (amp * r.nextGaussian() * Math.exp(-j / (sr * 0.008)));
                }
            } else {
                float[] nz = VoiceTest.readWav(dir + "/" + noiseFiles[k] + ".wav");
                for (int i = 0; i < noise.length; i++) noise[i] = nz[i % nz.length];
            }
            VoiceTest.SR = sr;
            double g = Math.sqrt(VoiceTest.pow(clean) / VoiceTest.pow(noise) / Math.pow(10, snrDb / 10));
            float[] noisy = new float[clean.length];
            for (int i = 0; i < noisy.length; i++) noisy[i] = clean[i] + (float) (g * noise[i]);

            float[] ref = VoiceTest.resampleRef(clean), noisy48 = VoiceTest.resampleRef(noisy);
            float[] y0 = VoiceTest.run(noisy, 0f, 0f, 1f);
            float[] y1 = VoiceTest.run(noisy, 1f, 0f, 1f);
            float[] y8 = VoiceTest.run(noisy, 0.8f, 0f, 1f);
            double[] m0 = VoiceTest.metrics(ref, noisy48, y0), m1 = VoiceTest.metrics(ref, noisy48, y1), m8 = VoiceTest.metrics(ref, noisy48, y8);
            System.out.printf("%-24s %13.1f dB %10.1f dB / %5.1f dB %10.1f dB / %5.1f dB%n", noiseFiles[k], m0[1], m1[1], m1[0], m8[1], m8[0]);
            tot[0] += m0[1]; tot[1] += m1[1]; tot[2] += m8[1]; cnt++;
            if (outDir != null) {
                VoiceTest.writeWav(outDir + "/real_" + noiseFiles[k] + "_before.wav", y0, 48000);
                VoiceTest.writeWav(outDir + "/real_" + noiseFiles[k] + "_after.wav", y8, 48000);
            }
        }
        System.out.printf("%-24s %13.1f dB %10.1f dB          %10.1f dB%n", "AVERAGE", tot[0] / cnt, tot[1] / cnt, tot[2] / cnt);
    }
}
