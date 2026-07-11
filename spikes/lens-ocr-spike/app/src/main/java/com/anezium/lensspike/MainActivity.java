package com.anezium.lensspike;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "LENS_SPIKE";
    private static final long BATTERY_SAMPLE_MS = 10_000L;
    private static final long THROUGHPUT_WINDOW_MS = 10_000L;
    private static final int LATENCY_WINDOW_SIZE = 30;

    private enum Mode {
        LATIN,
        JAPANESE
    }

    private PreviewView previewView;
    private LensOverlayView overlayView;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private TextRecognizer latinRecognizer;
    private TextRecognizer japaneseRecognizer;
    private Mode currentMode = Mode.LATIN;
    private final AtomicBoolean analyzerBusy = new AtomicBoolean(false);
    private final AtomicLong totalFramesAnalyzed = new AtomicLong(0L);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object statsLock = new Object();
    private final ArrayDeque<Long> latencyWindow = new ArrayDeque<>();
    private final ArrayDeque<Long> completionWindow = new ArrayDeque<>();
    private final long sessionStartMs = SystemClock.elapsedRealtime();
    private float batteryTempC = Float.NaN;

    private final Runnable batterySampler = new Runnable() {
        @Override
        public void run() {
            sampleBatteryTemperature(true);
            mainHandler.postDelayed(this, BATTERY_SAMPLE_MS);
        }
    };

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    overlayView.setStatus("CAMERA READY");
                    startCamera();
                } else {
                    overlayView.setStatus("CAMERA PERMISSION REQUIRED");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);

        setContentView(R.layout.activity_main);
        FrameLayout rootView = findViewById(R.id.rootView);
        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);

        previewView.setBackgroundColor(Color.BLACK);
        overlayView.setBackgroundColor(Color.TRANSPARENT);
        previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        rootView.setOnClickListener(view -> toggleMode());
        overlayView.setMode(currentMode.name());

        cameraExecutor = Executors.newSingleThreadExecutor();
        latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        japaneseRecognizer = TextRecognition.getClient(
                new JapaneseTextRecognizerOptions.Builder().build()
        );

        sampleBatteryTemperature(false);
        mainHandler.postDelayed(batterySampler, BATTERY_SAMPLE_MS);
        hideSystemUi();
        ensureCameraPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (hasCameraPermission()) {
            startCamera();
        }
    }

    @Override
    protected void onPause() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        analyzerBusy.set(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(batterySampler);
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (latinRecognizer != null) {
            latinRecognizer.close();
        }
        if (japaneseRecognizer != null) {
            japaneseRecognizer.close();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() == 0
                && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
            toggleMode();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void ensureCameraPermission() {
        if (hasCameraPermission()) {
            overlayView.setStatus("CAMERA READY");
            startCamera();
        } else {
            overlayView.setStatus("REQUESTING CAMERA");
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        if (cameraProvider != null) {
            bindCameraUseCases();
            return;
        }

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception exception) {
                Log.e(TAG, "cameraProviderFailure", exception);
                overlayView.setStatus("CAMERA ERROR");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null || !hasCameraPermission()) {
            return;
        }

        Preview preview = new Preview.Builder()
                .setTargetRotation(previewView.getDisplay() != null
                        ? previewView.getDisplay().getRotation()
                        : Surface.ROTATION_0)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setTargetRotation(previewView.getDisplay() != null
                        ? previewView.getDisplay().getRotation()
                        : Surface.ROTATION_0)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor, new TextAnalyzer());

        List<SelectorCandidate> candidates = Arrays.asList(
                new SelectorCandidate("WORLD CAM", CameraSelector.DEFAULT_BACK_CAMERA),
                new SelectorCandidate("ALT CAM", CameraSelector.DEFAULT_FRONT_CAMERA),
                new SelectorCandidate(
                        "AUTO CAM",
                        new CameraSelector.Builder()
                                .addCameraFilter(cameras -> cameras.isEmpty()
                                        ? cameras
                                        : Collections.singletonList(cameras.get(0)))
                                .build()
                )
        );

        cameraProvider.unbindAll();
        for (SelectorCandidate candidate : candidates) {
            try {
                Camera ignored = cameraProvider.bindToLifecycle(this, candidate.selector, preview, analysis);
                overlayView.setStatus(candidate.label);
                return;
            } catch (Exception exception) {
                Log.w(TAG, "cameraBindFailed label=" + candidate.label, exception);
            }
        }

        overlayView.setStatus("NO CAMERA");
        Log.e(TAG, "cameraBindFailed all candidates");
    }

    private void toggleMode() {
        currentMode = currentMode == Mode.LATIN ? Mode.JAPANESE : Mode.LATIN;
        clearRollingStats();
        overlayView.setMode(currentMode.name());
        overlayView.setStatus("MODE " + currentMode.name());
        Log.i(TAG, "modeSwitch mode=" + currentMode.name());
    }

    private void clearRollingStats() {
        synchronized (statsLock) {
            latencyWindow.clear();
            completionWindow.clear();
        }
    }

    private void sampleBatteryTemperature(boolean logSample) {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            batteryTempC = tenths == Integer.MIN_VALUE ? Float.NaN : tenths / 10f;
        } else {
            batteryTempC = Float.NaN;
        }

        overlayView.updateBatteryTemp(batteryTempC);
        if (logSample) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                    .format(new Date());
            Log.i(TAG, String.format(Locale.US, "batterySample timestamp=%s temp=%s",
                    timestamp, formatTempForLog(batteryTempC)));
        }
    }

    private LensOverlayView.Metrics recordOcrCompletion(long latencyMs, long nowMs) {
        synchronized (statsLock) {
            latencyWindow.addLast(latencyMs);
            while (latencyWindow.size() > LATENCY_WINDOW_SIZE) {
                latencyWindow.removeFirst();
            }

            completionWindow.addLast(nowMs);
            while (!completionWindow.isEmpty()
                    && nowMs - completionWindow.peekFirst() > THROUGHPUT_WINDOW_MS) {
                completionWindow.removeFirst();
            }

            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            long sum = 0L;
            for (long value : latencyWindow) {
                min = Math.min(min, value);
                max = Math.max(max, value);
                sum += value;
            }

            if (latencyWindow.isEmpty()) {
                min = 0L;
                max = 0L;
            }

            long avg = latencyWindow.isEmpty() ? 0L : Math.round(sum / (double) latencyWindow.size());
            long windowStart = Math.max(sessionStartMs, nowMs - THROUGHPUT_WINDOW_MS);
            double windowSeconds = Math.max(1L, nowMs - windowStart) / 1000.0;
            double hz = completionWindow.size() / windowSeconds;
            return new LensOverlayView.Metrics(
                    avg,
                    min,
                    max,
                    hz,
                    totalFramesAnalyzed.get(),
                    nowMs - sessionStartMs
            );
        }
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            @SuppressWarnings("deprecation")
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private String formatOcrLog(Mode mode, long latencyMs, LensOverlayView.Metrics metrics, int blocks) {
        return String.format(Locale.US,
                "mode=%s latencyMs=%d avgMs=%d hz=%.1f blocks=%d temp=%s",
                mode.name(),
                latencyMs,
                metrics.avgLatencyMs,
                metrics.hz,
                blocks,
                formatTempForLog(batteryTempC));
    }

    private static String formatTempForLog(float tempC) {
        if (Float.isNaN(tempC)) {
            return "NaN";
        }
        return String.format(Locale.US, "%.1f", tempC);
    }

    private final class TextAnalyzer implements ImageAnalysis.Analyzer {
        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            totalFramesAnalyzed.incrementAndGet();

            if (imageProxy.getImage() == null) {
                imageProxy.close();
                return;
            }

            if (!analyzerBusy.compareAndSet(false, true)) {
                imageProxy.close();
                return;
            }

            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
            int imageWidth = (rotationDegrees == 90 || rotationDegrees == 270)
                    ? imageProxy.getHeight()
                    : imageProxy.getWidth();
            int imageHeight = (rotationDegrees == 90 || rotationDegrees == 270)
                    ? imageProxy.getWidth()
                    : imageProxy.getHeight();
            Mode modeSnapshot = currentMode;
            TextRecognizer recognizer = modeSnapshot == Mode.LATIN ? latinRecognizer : japaneseRecognizer;
            InputImage inputImage = InputImage.fromMediaImage(imageProxy.getImage(), rotationDegrees);
            long processStartMs = SystemClock.elapsedRealtime();

            recognizer.process(inputImage)
                    .addOnSuccessListener(ContextCompat.getMainExecutor(MainActivity.this), text -> {
                        long nowMs = SystemClock.elapsedRealtime();
                        long latencyMs = nowMs - processStartMs;
                        List<Rect> blocks = extractBlocks(text);
                        LensOverlayView.Metrics metrics = recordOcrCompletion(latencyMs, nowMs);
                        overlayView.updateOcrResult(
                                modeSnapshot.name(),
                                imageWidth,
                                imageHeight,
                                blocks,
                                blocks.size(),
                                metrics
                        );
                        Log.i(TAG, formatOcrLog(modeSnapshot, latencyMs, metrics, blocks.size()));
                    })
                    .addOnFailureListener(ContextCompat.getMainExecutor(MainActivity.this), error ->
                            Log.w(TAG, "ocrFailure mode=" + modeSnapshot.name(), error))
                    .addOnCompleteListener(ContextCompat.getMainExecutor(MainActivity.this), task -> {
                        analyzerBusy.set(false);
                        imageProxy.close();
                    });
        }

        private List<Rect> extractBlocks(Text text) {
            List<Rect> result = new ArrayList<>();
            for (Text.TextBlock block : text.getTextBlocks()) {
                Rect box = block.getBoundingBox();
                if (box != null) {
                    result.add(new Rect(box));
                }
            }
            return result;
        }
    }

    private static final class SelectorCandidate {
        final String label;
        final CameraSelector selector;

        SelectorCandidate(String label, CameraSelector selector) {
            this.label = label;
            this.selector = selector;
        }
    }
}
