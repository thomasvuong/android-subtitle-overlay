package com.example.subtitles;

import android.content.Context;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SherpaRealtimeInstrumentedTest {

    @Test
    public void feedsClipAtRealTime() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File wav = new File(ctx.getExternalFilesDir(null), "clip16k.wav");
        assertTrue(wav.exists());

        byte[] wavBytes = Files.readAllBytes(wav.toPath());
        // PCM starts at byte 44 (WAV header)
        short[] pcm = new short[(wavBytes.length - 44) / 2];
        for (int i = 0; i < pcm.length; i++) {
            int idx = 44 + i * 2;
            pcm[i] = (short) ((wavBytes[idx + 1] & 0xFF) << 8 | (wavBytes[idx] & 0xFF));
        }

        com.example.subtitles.model.transcription.core.SherpaStreamTranscriber t = com.example.subtitles.model.transcription.core.SherpaStreamTranscriber.getInstance(ctx);

        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> last = new AtomicReference<>("");
        AtomicInteger updates = new AtomicInteger();

        t.setListener(new com.example.subtitles.model.transcription.core.VoskStreamTranscriber.Listener() {
            @Override
            public void onTranscriptionUpdate(String partial, String full) {
                last.set(full);
                updates.incrementAndGet();
                Log.i("SherpaE2E", "update t=" + System.currentTimeMillis() + " text=" + full);
            }

            @Override
            public void onFinalResult(com.example.subtitles.model.transcription.correction.transcriptSegment s) {
                Log.i("SherpaE2E", "final");
            }

            @Override
            public void onError(Exception e) {
                Log.e("SherpaE2E", "error", e);
            }

            @Override
            public void onModelChange(String a, String b) {
                ready.countDown();
            }
        });

        t.switchLanguageAsync("vi");
        assertTrue(ready.await(60, TimeUnit.SECONDS));

        int n = 1600;
        long start = System.currentTimeMillis();
        for (int i = 0; i + n <= pcm.length; i += n) {
            short[] c = Arrays.copyOfRange(pcm, i, i + n);
            t.acceptAudio(new com.example.subtitles.model.transcription.core.TaggedAudioChunk(c, (long) (i / 16)));
            long due = start + (i + n) / 16L;
            long wait = due - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
        }

        Thread.sleep(3000);
        Log.i("SherpaE2E", "updates=" + updates.get() + " last=" + last.get());
        t.stop();
        assertTrue(updates.get() > 0);
    }
}

