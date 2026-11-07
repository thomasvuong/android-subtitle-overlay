package com.example.subtitles.model.transcription.core;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.example.subtitles.model.transcription.correction.transcriptSegment;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class SherpaStreamTranscriber {

    private static final String TAG = "SherpaTranscriber";
    private static final int SAMPLE_RATE = 16000;
    private static final int VAD_WINDOW = 512;
    private static final int PREROLL_SAMPLES = 9600;
    private static final int DECODE_INTERVAL_SAMPLES = 6400;
    private static final int MIN_DECODE_SAMPLES = 8000;
    private static final int MAX_SEGMENT_SAMPLES = 192000;
    private static final int MAX_PREVIOUS_WORDS = 10;
    private static final int NUM_THREADS = 4;
    private static final String ASSET_DIR = "sherpa_vi";

    private static volatile SherpaStreamTranscriber instance;

    private final Context context;
    private volatile boolean running;
    private Thread worker;
    private volatile VoskStreamTranscriber.Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<TaggedAudioChunk> queue = new LinkedBlockingQueue<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private OfflineRecognizer recognizer;
    private Vad vad;

    private final FloatBuffer pending = new FloatBuffer();
    private final FloatBuffer preRoll = new FloatBuffer();
    private final FloatBuffer segment = new FloatBuffer();
    private boolean inSegment;
    private boolean segmentEnded;
    private long segStartMs;
    private long segEndMs;
    private int lastDecodeSize;
    private String previousText = "";
    private String lastSent = "";
    private volatile boolean segmentOpen;

    private SherpaStreamTranscriber(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized SherpaStreamTranscriber getInstance(Context context) {
        if (instance == null) {
            instance = new SherpaStreamTranscriber(context);
        }
        return instance;
    }

    private static final class FloatBuffer {
        float[] data = new float[32000];
        int size;

        void add(float[] src, int off, int len) {
            if (size + len > data.length) {
                int newCapacity = Math.max(data.length * 2, size + len);
                data = Arrays.copyOf(data, newCapacity);
            }
            System.arraycopy(src, off, data, size, len);
            size += len;
        }

        void clear() {
            size = 0;
        }

        float[] toArray() {
            return Arrays.copyOf(data, size);
        }

        void dropFront(int n) {
            if (n >= size) {
                size = 0;
                return;
            }
            System.arraycopy(data, n, data, 0, size - n);
            size -= n;
        }

        void keepLast(int n) {
            if (size > n) {
                dropFront(size - n);
            }
        }
    }

    public void setListener(VoskStreamTranscriber.Listener l) {
        listener = l;
    }

    public void switchLanguageAsync(String lang) {
        if (!"vi".equals(lang)) {
            Log.w(TAG, "Only Vietnamese language is supported");
            return;
        }
        synchronized (this) {
            if (running) {
                return;
            }
        }
        loader.submit(() -> {
            try {
                synchronized (this) {
                    if (running) {
                        return;
                    }
                    loadModels();
                    running = true;
                    worker = new Thread(SherpaStreamTranscriber.this::workerLoop, "SherpaWorker");
                    worker.start();
                }
                main.post(() -> {
                    if (listener != null) {
                        listener.onModelChange("vi", ASSET_DIR);
                    }
                });
            } catch (Throwable t) {
                releaseModels();
                postError(new Exception("Could not load Vietnamese model", t));
            }
        });
    }

    private void loadModels() {
        FeatureConfig featCfg = new FeatureConfig();
        featCfg.setSampleRate(SAMPLE_RATE);
        featCfg.setFeatureDim(80);

        OfflineTransducerModelConfig transducerCfg = new OfflineTransducerModelConfig();
        transducerCfg.setEncoder(ASSET_DIR + "/encoder.int8.onnx");
        transducerCfg.setDecoder(ASSET_DIR + "/decoder.onnx");
        transducerCfg.setJoiner(ASSET_DIR + "/joiner.int8.onnx");

        OfflineModelConfig modelCfg = new OfflineModelConfig();
        modelCfg.setTransducer(transducerCfg);
        modelCfg.setTokens(ASSET_DIR + "/tokens.txt");
        modelCfg.setNumThreads(NUM_THREADS);
        modelCfg.setProvider("cpu");
        modelCfg.setDebug(false);
        modelCfg.setModelType("transducer");

        OfflineRecognizerConfig cfg = new OfflineRecognizerConfig();
        cfg.setFeatConfig(featCfg);
        cfg.setModelConfig(modelCfg);
        cfg.setDecodingMethod("modified_beam_search");
        cfg.setMaxActivePaths(4);

        recognizer = new OfflineRecognizer(context.getAssets(), cfg);

        SileroVadModelConfig sileroCfg = new SileroVadModelConfig();
        sileroCfg.setModel(ASSET_DIR + "/silero_vad.onnx");
        sileroCfg.setThreshold(0.30f);
        sileroCfg.setMinSilenceDuration(0.50f);
        sileroCfg.setMinSpeechDuration(0.10f);
        sileroCfg.setWindowSize(VAD_WINDOW);
        sileroCfg.setMaxSpeechDuration(20f);

        VadModelConfig vadCfg = new VadModelConfig();
        vadCfg.setSileroVadModelConfig(sileroCfg);
        vadCfg.setSampleRate(SAMPLE_RATE);
        vadCfg.setNumThreads(1);
        vadCfg.setProvider("cpu");
        vadCfg.setDebug(false);

        vad = new Vad(context.getAssets(), vadCfg);
    }

    private void releaseModels() {
        if (recognizer != null) {
            recognizer.release();
            recognizer = null;
        }
        if (vad != null) {
            vad.release();
            vad = null;
        }
    }

    public void acceptAudio(TaggedAudioChunk c) {
        if (running) {
            queue.offer(c);
        }
    }

    private void workerLoop() {
        try {
            while (running) {
                List<TaggedAudioChunk> batch = new ArrayList<>();
                batch.add(queue.take());
                queue.drainTo(batch);
                for (TaggedAudioChunk c : batch) {
                    float[] a = c.getFloatAudio();
                    pending.add(a, 0, a.length);
                    runVad(c.getTime());
                }
                decodeIfDue();
            }
        } catch (InterruptedException e) {
            Log.d(TAG, "worker stopped");
        } catch (Exception e) {
            postError(e);
        }
    }

    private void runVad(long timeMs) {
        float[] window = new float[VAD_WINDOW];
        int read = 0;
        while (pending.size - read >= VAD_WINDOW) {
            System.arraycopy(pending.data, read, window, 0, VAD_WINDOW);
            read += VAD_WINDOW;
            vad.acceptWaveform(window);
            if (!vad.empty()) {
                vad.clear();
            }
            boolean speech = vad.isSpeechDetected();
            handleWindow(window, speech, timeMs);
        }
        pending.dropFront(read);
    }

    private void handleWindow(float[] w, boolean speech, long t) {
        if (!inSegment) {
            if (speech) {
                segment.clear();
                segment.add(preRoll.data, 0, preRoll.size);
                segment.add(w, 0, w.length);
                preRoll.clear();
                inSegment = true;
                segmentOpen = true;
                segmentEnded = false;
                lastDecodeSize = 0;
                segStartMs = t;
                segEndMs = t;
            } else {
                preRoll.add(w, 0, w.length);
                preRoll.keepLast(PREROLL_SAMPLES);
            }
            return;
        }
        if (segmentEnded) {
            preRoll.add(w, 0, w.length);
            preRoll.keepLast(PREROLL_SAMPLES);
            return;
        }
        segment.add(w, 0, w.length);
        segEndMs = t;
        if (!speech || segment.size >= MAX_SEGMENT_SAMPLES) {
            finishSegment();
        }
    }

    private void decodeIfDue() {
        if (!inSegment) {
            return;
        }
        if (segmentEnded) {
            finishSegment();
            return;
        }
        if (segment.size >= MIN_DECODE_SAMPLES && segment.size - lastDecodeSize >= DECODE_INTERVAL_SAMPLES) {
            lastDecodeSize = segment.size;
            String text = decode(segment.toArray());
            if (!text.isEmpty()) {
                notifyUi(join(previousText, text));
            }
        }
    }

    private void finishSegment() {
        final long start = segStartMs;
        final long end = segEndMs;
        boolean full = segment.size >= MAX_SEGMENT_SAMPLES;
        int cut = full ? quietestCut() : segment.size;
        String text = decode(Arrays.copyOf(segment.data, cut));
        if (!text.isEmpty()) {
            main.post(() -> {
                VoskStreamTranscriber.Listener l = listener;
                if (l != null) l.onFinalResult(new transcriptSegment(start, end, text));
            });
            previousText = lastWords(join(previousText, text), MAX_PREVIOUS_WORDS);
            notifyUi(previousText);
        }
        if (full) {
            segment.dropFront(cut);
            lastDecodeSize = 0;
            segStartMs = segEndMs;
            segmentEnded = false;
            return;
        }
        preRoll.clear();
        preRoll.add(segment.data, Math.max(0, segment.size - PREROLL_SAMPLES), Math.min(segment.size, PREROLL_SAMPLES));
        segment.clear();
        inSegment = false;
        segmentOpen = false;
        segmentEnded = false;
        lastDecodeSize = 0;
    }

    private int quietestCut() {
        int from = Math.max(0, segment.size - 2 * SAMPLE_RATE);
        int frame = SAMPLE_RATE / 5;
        int best = segment.size;
        double bestEnergy = Double.MAX_VALUE;
        for (int s = from; s + frame <= segment.size; s += frame / 4) {
            double e = 0;
            for (int i = s; i < s + frame; i++) {
                e += segment.data[i] * segment.data[i];
            }
            if (e < bestEnergy) {
                bestEnergy = e;
                best = s + frame / 2;
            }
        }
        return best;
    }

    private String decode(float[] samples) {
        long t0 = System.nanoTime();
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE);
            recognizer.decode(stream);
            return normalize(recognizer.getResult(stream).getText());
        } finally {
            stream.release();
            Log.d(TAG, "decode samples=" + samples.length + " ms=" + (System.nanoTime() - t0) / 1000000L);
        }
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(new Locale("vi")).replaceAll("\\s+", " ");
        if (s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase(new Locale("vi")) + s.substring(1);
    }

    private void notifyUi(String full) {
        if (full.equals(lastSent)) return;
        lastSent = full;
        final String prev = previousText;
        main.post(() -> {
            VoskStreamTranscriber.Listener l = listener;
            if (l != null) l.onTranscriptionUpdate(prev, full);
        });
    }

    private void postError(Exception e) {
        main.post(() -> {
            VoskStreamTranscriber.Listener l = listener;
            if (l != null) l.onError(e);
        });
    }

    private static String join(String a, String b) {
        return (a + " " + b).trim();
    }

    private static String lastWords(String text, int n) {
        String[] w = text.trim().split("\\s+");
        if (w.length <= n) return text.trim();
        return String.join(" ", Arrays.copyOfRange(w, w.length - n, w.length));
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        queue.clear();
        releaseModels();
        pending.clear();
        preRoll.clear();
        segment.clear();
        inSegment = false;
        segmentOpen = false;
        segmentEnded = false;
        lastDecodeSize = 0;
        previousText = "";
        lastSent = "";
    }

    public void destroy() {
        stop();
        loader.shutdownNow();
    }

    public boolean inTheMiddle() {
        return segmentOpen;
    }
}
