package com.cleanvoice.player;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity implements AudioEngine.Listener {

    private static final int REQ_OPEN = 1;
    private static final int REQ_SAVE = 2;

    private AudioEngine engine;
    private SharedPreferences prefs;

    private Uri currentUri;
    private String currentName = "recording";
    private boolean userSeeking;
    private volatile boolean exporting;

    private TextView txtFile, txtPos, txtDur, txtNoise, txtClarity, txtVolume, txtSave;
    private SeekBar seekPosition, seekNoise, seekClarity, seekVolume;
    private Switch swMaster, swNoise, swClarity, swVolume;
    private Button btnPlay, btnStop, btnSave, btnOpen, btnMax;
    private ProgressBar progressSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        engine = new AudioEngine(this, this);

        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
                top = i.top; bottom = i.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        txtFile = findViewById(R.id.txtFile);
        txtPos = findViewById(R.id.txtPos);
        txtDur = findViewById(R.id.txtDur);
        txtNoise = findViewById(R.id.txtNoise);
        txtClarity = findViewById(R.id.txtClarity);
        txtVolume = findViewById(R.id.txtVolume);
        txtSave = findViewById(R.id.txtSave);
        seekPosition = findViewById(R.id.seekPosition);
        seekNoise = findViewById(R.id.seekNoise);
        seekClarity = findViewById(R.id.seekClarity);
        seekVolume = findViewById(R.id.seekVolume);
        swMaster = findViewById(R.id.swMaster);
        swNoise = findViewById(R.id.swNoise);
        swClarity = findViewById(R.id.swClarity);
        swVolume = findViewById(R.id.swVolume);
        btnPlay = findViewById(R.id.btnPlay);
        btnStop = findViewById(R.id.btnStop);
        btnSave = findViewById(R.id.btnSave);
        btnOpen = findViewById(R.id.btnOpen);
        btnMax = findViewById(R.id.btnMax);
        progressSave = findViewById(R.id.progressSave);

        // Restore saved settings (defaults: strong cleaning, clear voice, 2x volume)
        swMaster.setChecked(prefs.getBoolean("master", true));
        swNoise.setChecked(prefs.getBoolean("noiseOn", true));
        swClarity.setChecked(prefs.getBoolean("clarityOn", true));
        swVolume.setChecked(prefs.getBoolean("volumeOn", true));
        seekNoise.setProgress(prefs.getInt("noise2", 90));
        seekClarity.setProgress(prefs.getInt("clarity2", 40));
        seekVolume.setProgress(prefs.getInt("volume2", 50));

        SeekBar.OnSeekBarChangeListener effectListener = new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { applyEffects(); }
        };
        seekNoise.setOnSeekBarChangeListener(effectListener);
        seekClarity.setOnSeekBarChangeListener(effectListener);
        seekVolume.setOnSeekBarChangeListener(effectListener);
        CompoundButton.OnCheckedChangeListener switchListener = (b, c) -> applyEffects();
        swMaster.setOnCheckedChangeListener(switchListener);
        swNoise.setOnCheckedChangeListener(switchListener);
        swClarity.setOnCheckedChangeListener(switchListener);
        swVolume.setOnCheckedChangeListener(switchListener);
        applyEffects();

        btnOpen.setOnClickListener(v -> pickFile());
        btnPlay.setOnClickListener(v -> {
            if (!engine.hasFile()) { pickFile(); return; }
            if (engine.isPlaying()) engine.pause(); else engine.play();
        });
        btnStop.setOnClickListener(v -> engine.stop());
        btnMax.setOnClickListener(v -> {
            swMaster.setChecked(true);
            swNoise.setChecked(true);
            swClarity.setChecked(true);
            swVolume.setChecked(true);
            seekNoise.setProgress(100);
            seekClarity.setProgress(50);
            seekVolume.setProgress(100); // 2x
        });
        btnSave.setOnClickListener(v -> startSave());

        seekPosition.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                long target = engine.getDurationUs() * s.getProgress() / 1000;
                engine.seekTo(target);
                txtPos.setText(fmt(target));
            }
        });

        setControlsEnabled(false);
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    /** Supports "Open with" and "Share" from file managers, WhatsApp, recorders, etc. */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) openUri(uri);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*", "application/ogg"});
        startActivityForResult(i, REQ_OPEN);
    }

    private void startSave() {
        if (currentUri == null) {
            Toast.makeText(this, R.string.open_first, Toast.LENGTH_SHORT).show();
            return;
        }
        String base = currentName.replaceAll("\\.[^.]*$", "");
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_TITLE, base + "_clean.wav");
        startActivityForResult(i, REQ_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_OPEN) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            openUri(uri);
        } else if (requestCode == REQ_SAVE) {
            runExport(uri);
        }
    }

    private void openUri(Uri uri) {
        txtFile.setText("…");
        new Thread(() -> {
            try {
                long dur = engine.load(uri);
                String name = queryName(uri);
                runOnUiThread(() -> {
                    currentUri = uri;
                    currentName = name;
                    txtFile.setText(name);
                    txtDur.setText(fmt(dur));
                    txtPos.setText(fmt(0));
                    seekPosition.setProgress(0);
                    setControlsEnabled(true);
                    engine.play();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtFile.setText(R.string.no_file);
                    Toast.makeText(this, getString(R.string.open_failed, String.valueOf(e.getMessage())),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void runExport(Uri output) {
        final float noise = effNoise(), clarity = effClarity(), volume = effVolume();
        final Uri input = currentUri;
        exporting = true;
        btnSave.setEnabled(false);
        progressSave.setVisibility(View.VISIBLE);
        progressSave.setProgress(0);
        txtSave.setText(getString(R.string.saving, 0));
        new Thread(() -> {
            try {
                AudioExporter.export(getApplicationContext(), input, output, noise, clarity, volume, pct -> {
                    runOnUiThread(() -> {
                        progressSave.setProgress(pct);
                        txtSave.setText(getString(R.string.saving, pct));
                    });
                    return !isFinishing();
                });
                runOnUiThread(() -> {
                    txtSave.setText(R.string.saved);
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> txtSave.setText(getString(R.string.save_failed, String.valueOf(e.getMessage()))));
            } finally {
                exporting = false;
                runOnUiThread(() -> {
                    btnSave.setEnabled(currentUri != null);
                    progressSave.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    // ---------------- effects ----------------

    private float effNoise() { return swMaster.isChecked() && swNoise.isChecked() ? seekNoise.getProgress() / 100f : 0f; }
    private float effClarity() { return swMaster.isChecked() && swClarity.isChecked() ? seekClarity.getProgress() / 100f : 0f; }
    private float effVolume() { return swMaster.isChecked() && swVolume.isChecked() ? 1f + seekVolume.getProgress() / 100f : 1f; }

    private void applyEffects() {
        txtNoise.setText(seekNoise.getProgress() + "%");
        txtClarity.setText(seekClarity.getProgress() + "%");
        txtVolume.setText(String.format(Locale.US, "%.1fx", 1f + seekVolume.getProgress() / 100f));
        seekNoise.setEnabled(swNoise.isChecked());
        seekClarity.setEnabled(swClarity.isChecked());
        seekVolume.setEnabled(swVolume.isChecked());

        // The engine gets final values; master switch is the instant A/B "original" comparison.
        engine.setNoiseReduction(swNoise.isChecked() ? seekNoise.getProgress() / 100f : 0f);
        engine.setVoiceClarity(swClarity.isChecked() ? seekClarity.getProgress() / 100f : 0f);
        engine.setVolume(swVolume.isChecked() ? 1f + seekVolume.getProgress() / 100f : 1f);
        engine.setEnhancementsEnabled(swMaster.isChecked());

        prefs.edit()
                .putBoolean("master", swMaster.isChecked())
                .putBoolean("noiseOn", swNoise.isChecked())
                .putBoolean("clarityOn", swClarity.isChecked())
                .putBoolean("volumeOn", swVolume.isChecked())
                .putInt("noise2", seekNoise.getProgress())
                .putInt("clarity2", seekClarity.getProgress())
                .putInt("volume2", seekVolume.getProgress())
                .apply();
    }

    // ---------------- engine callbacks ----------------

    @Override
    public void onProgress(long positionUs, long durationUs) {
        if (userSeeking) return;
        txtPos.setText(fmt(positionUs));
        if (durationUs > 0) seekPosition.setProgress((int) (positionUs * 1000 / durationUs));
    }

    @Override
    public void onPlayStateChanged(boolean playing) {
        btnPlay.setText(playing ? R.string.pause : R.string.play);
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ---------------- helpers ----------------

    private void setControlsEnabled(boolean on) {
        btnStop.setEnabled(on);
        seekPosition.setEnabled(on);
        btnSave.setEnabled(on && !exporting);
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last != null ? last : "recording";
    }

    private static String fmt(long us) {
        long s = Math.max(0, us / 1_000_000);
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        engine.release(); // rotation doesn't recreate the activity (see manifest configChanges)
    }

    /** Small adapter so we only override what we need. */
    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { }
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }
}
