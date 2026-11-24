# Clean Voice Player (Android)

Offline audio player for voice recordings with **AI noise removal**:

- **AI noise removal (0–100%)** uses RNNoise, xiph.org's neural speech denoiser, plus:
  - a voice-detector gate that silences the gaps between words
  - a clap and knock remover
  - a light spectral polish for steady hiss

  It removes fans, AC, traffic, vacuum cleaners, people talking in the background, claps, knocks, footsteps, typing, chewing, dogs and hiss.
- **Voice clarity** gives crisper speech and evens out quiet and loud words. It pushes leftover noise down instead of boosting it.
- **Volume boost (1x–5x)** includes a limiter, so loud parts never crackle.
- **Enhancements on/off switch** lets you compare with the original while playing. There's also a **one-tap max clean** preset.
- **Save cleaned audio** as a 48 kHz WAV copy. The original file is not changed.
- Opens mp3, m4a/aac, wav, ogg/opus (WhatsApp voice notes), flac, amr, 3gp and the audio in mp4 videos.
- 100% offline: no internet permission. Needs Android 8.0+ on an ARM phone (almost all phones).

### Measured results
These come from the build test bench: real speech (Microsoft MS-SNSD) mixed with real noise at 5 dB SNR, at 100% strength.

| Noise | Removed | Voice quality before → after |
|---|---|---|
| People talking (babble) | 25–26 dB | 6.8 → 8.3–10.3 dB |
| Airport announcements | 36 dB | 6.6 → 10.3 dB |
| Air conditioner | 35 dB | 6.7 → 11.4 dB |
| Vacuum cleaner | 25 dB | 6.9 → 12.8 dB |
| Typing | 44 dB | 6.7 → 13.4 dB |
| Chewing | 42 dB | 6.9 → 14.4 dB |
| Door slams | 47 dB | 6.7 → 16.1 dB |
| Claps | 23 dB | 7.0 → 14.4 dB |

Limits: coughs, a crying baby and a voice as loud as the main speaker are human vocal sounds. They are only partly reduced.

## Get the APK – no Android Studio needed (GitHub, free)

1. Create a new **private** repository on github.com.
2. Upload all files from this folder. Include the hidden `.github` folder, or create
   `.github/workflows/build-apk.yml` in the web editor and paste its contents.
3. Open the **Actions** tab. The "Build APK" workflow runs automatically, which takes about 4–6 minutes.
4. Open the finished run and download **CleanVoicePlayer-APK** (a zip file containing the `.apk`).
5. Copy it to your phone, tap it and allow "Install unknown apps" when asked.

## Or build with Android Studio

Open the folder in Android Studio, let it sync, then choose **Build ▸ Build APK(s)**, or press Run with your phone connected.

## How it works
`app/src/main/java/com/cleanvoice/player/dsp/AudioProcessor.java` runs these steps in order:
1. Resample to 48 kHz (`Resampler`)
2. Remove claps and knocks (`ClapSuppressor`)
3. RNNoise denoising (`app/src/main/cpp`)
4. Gate the gaps between words using RNNoise's voice detector
5. Spectral polish (`SpectralDenoiser`)
6. Voice clarity EQ and dynamics
7. Volume boost and limiter

The RNNoise model weights are downloaded and checksum-verified at build time. To rerun the test bench, see `tools/dsptest` and `.github/workflows/build-apk.yml`.

Third-party: RNNoise © Xiph.Org Foundation, Jean-Marc Valin et al. (BSD-3, see `app/src/main/cpp/rnnoise/COPYING`). Test clips are from MS-SNSD © Microsoft (MIT).
