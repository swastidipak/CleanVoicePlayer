# Clean Voice Player (Android)

Offline audio player for voice recordings, with:

- **Background noise removal (0–100%)** – removes hiss, fan/AC hum, traffic, crowd murmur, mains hum and rumble.
  It learns the noise automatically from the recording and keeps adapting if the noise changes.
- **Voice clarity** – cuts muddy/boomy tones, boosts speech frequencies (around 3 kHz), and evens out quiet and loud words.
- **Volume boost (1x–5x)** – with a built-in limiter, so loud parts don't crackle or distort.
- **Enhancements on/off switch** – flip it while playing to compare with the original.
- **One-tap max clean** preset.
- **Save cleaned audio** as a WAV copy. The original file is not changed.
- Opens mp3, m4a/aac, wav, ogg, opus (WhatsApp voice notes), flac, amr, 3gp and the audio in mp4 videos.
  You can also use **Share** or **Open with** from WhatsApp or a file manager.
- 100% offline: no internet permission, no ads, no tracking. Needs Android 8.0 or newer.

## Get the APK – no Android Studio needed (GitHub, free)

1. Create a new **private** repository on github.com.
2. Upload all files from this folder. Include the hidden `.github` folder, or create
   `.github/workflows/build-apk.yml` in the web editor and paste its contents.
3. Open the **Actions** tab. The "Build APK" workflow runs automatically, which takes about 4–6 minutes.
4. Open the finished run and download **CleanVoicePlayer-APK** (a zip file containing the `.apk`).
5. Copy it to your phone, tap it and allow "Install unknown apps" when asked.

## Or build with Android Studio

Open the folder in Android Studio, let it sync, then choose **Build ▸ Build APK(s)**, or press Run with your phone connected.

## How the processing works (`app/src/main/java/com/cleanvoice/player/dsp/AudioProcessor.java`)

| Stage | Method |
|---|---|
| Noise removal | STFT (1024-point, 75% overlap) spectral subtraction. The noise profile is learned automatically from pauses, with musical-noise smoothing. A speech-presence gate pushes the gaps between words down at high settings. Hum and rumble below 80 Hz is removed. |
| Voice clarity | High-pass filter (70–120 Hz), −5 dB cut around 280 Hz, +7 dB presence boost around 3 kHz, gentle high-shelf boost, and a 4:1 voice compressor with make-up gain |
| Volume | Gain up to 5x (+14 dB), then a peak limiter with a −0.5 dBFS ceiling |

Measured on a test voice buried in hiss, hum and fan noise: at 100%, noise in the pauses drops by about 35 dB (close to silent), and voice-to-noise improves by about 6 dB.
Noise that overlaps the voice itself (another person talking, music) can only be reduced, not removed completely. At 100% the voice can start to sound slightly thin. 70–90% is usually the best balance.
