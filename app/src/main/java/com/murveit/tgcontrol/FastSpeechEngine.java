package com.murveit.tgcontrol;

/**
 * FastSpeechEngine - Algorithmic Overview
 *
 * This module pre-renders and manages a cache of spoken phrases in active memory
 * to bypass the inherent latency of Android's dynamic TextToSpeech synthesis.
 *
 * 1. INITIALIZATION (Parameters & Dependencies):
 * - Dependencies: Requires an active application Context and a fully initialized TextToSpeech engine.
 * - Parameters: None directly on instantiation, but relies on internal target phrase lists.
 *
 * 2. CALLING PROCEDURE:
 * - Instantiate the engine: `FastSpeechEngine engine = new FastSpeechEngine(context, initializedTts);`
 * - Trigger initialization phase: `engine.initializeCache();`
 * - To speak: `engine.speak("Fault");` or `engine.speak("73 miles per hour");`
 *
 * 3. INTERNAL ALGORITHMIC LOGIC (Step-by-step):
 * - Generates a massive target array containing standard calls ("Out", "Fault", "Let") and
 * a loop of MPH integers (e.g., "20 miles per hour" to "140 miles per hour") -- 246 phrases
 * total at the current MPH_MIN/MPH_MAX range.
 * - initializeCache() dispatches the check-and-load pass below to a dedicated background
 * thread and returns immediately, rather than running it inline on the caller's thread.
 * This was added 2026-07-20 after a real ANR ("Input dispatching timed out ... waited
 * 5003ms") whose trace showed the main thread blocked inside SoundPool.load(), called
 * from here via TextToSpeech.onInit()'s callback (which Android always dispatches on the
 * main thread) -- on a warm cache (all 246 .wav files already on disk from a prior
 * launch), that's up to 246 back-to-back native load() calls with no yielding, occasionally
 * slow enough on real hardware to trip Android's 5s input-dispatch watchdog. SoundPool.load()
 * is documented safe to call from any thread (the actual decode already happens on
 * SoundPool's own native thread pool), so there was no correctness reason for this to run
 * on the main thread in the first place -- see the background-thread-safety note on
 * soundMap below.
 * - Interrogates the Android internal storage directory specific to this cache.
 * - For each target phrase, sanitizes the string into a valid filesystem name.
 * - If the physical .wav file exists: Issues an immediate load command to the SoundPool.
 * - If the physical .wav file does NOT exist: Dispatches an asynchronous `synthesizeToFile`
 * command to the TTS engine.
 * - Utilizes an UtteranceProgressListener to intercept synthesis completion callbacks. Once a
 * file is successfully written to disk, it is dynamically loaded into the SoundPool.
 * - Maintains a map linking the exact textual phrase to the generated SoundPool ID.
 * - Upon a `speak(text)` invocation, evaluates the map. If a Sound ID exists, it triggers
 * immediate native playback via SoundPool. If it misses, it falls back to dynamic TTS
 * synthesis -- this existing fallback is also what makes the background-thread cache
 * population above safe: speak() calls made before the background pass finishes just take
 * the slower dynamic-TTS path instead of a cache hit, the same as any other cache miss.
 *
 * 4. EXPECTED OUTPUTS / SIDE EFFECTS:
 * - Side Effect: Creates a directory in internal storage containing dozens of .wav files.
 * - Side Effect: Allocates several megabytes of uncompressed PCM audio data in the Android Native Heap.
 * - Output: Auditory playback via the hardware speaker/Bluetooth.
 */

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FastSpeechEngine {
    private static final String TAG = "FastSpeechEngine";

    public static class EngineConstants {
        // The maximum concurrent audio streams SoundPool will mix hardware-side
        public static final int MAX_CONCURRENT_STREAMS = 2;
        // Directory name in internal storage for synthesized files
        public static final String CACHE_DIR_NAME = "fast_speech_cache";
        // TTS engine utterance ID prefix to route callbacks properly
        public static final String UTT_PREFIX = "fast_speech_";
        // Minimum and maximum serve speeds for pre-rendering
        public static final int MPH_MIN = 20;
        public static final int MPH_MAX = 140;
    }

    private final Context context;
    private final TextToSpeech tts;
    private final SoundPool soundPool;
    private final File cacheDir;

    // Maps the exact phrase (e.g. "73 miles per hour") to the SoundPool ID. A
    // ConcurrentHashMap, not a plain HashMap: loadIntoSoundPool() writes to this from
    // initializeCache()'s background thread (see that method) and, separately, from
    // the TTS UtteranceProgressListener's onDone() callback (already off the main
    // thread even before this change -- Android does not guarantee that callback
    // runs on any particular thread), while speak() reads it from whatever thread
    // the caller uses (normally the main thread). Plain HashMap is not safe under
    // concurrent access from multiple threads; this was a latent risk even before
    // initializeCache() itself moved to a background thread.
    private final Map<String, Integer> soundMap = new ConcurrentHashMap<>();
    private final List<String> targetPhrases = new ArrayList<>();

    private volatile long initialNativeMemory = 0;

    public FastSpeechEngine(Context context, TextToSpeech initializedTts) {
        this.context = context.getApplicationContext();
        this.tts = initializedTts;
        this.cacheDir = new File(this.context.getFilesDir(), EngineConstants.CACHE_DIR_NAME);

        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            this.soundPool = new SoundPool.Builder()
                    .setMaxStreams(EngineConstants.MAX_CONCURRENT_STREAMS)
                    .setAudioAttributes(audioAttributes)
                    .build();
        } else {
            this.soundPool = new SoundPool(EngineConstants.MAX_CONCURRENT_STREAMS, AudioManager.STREAM_MUSIC, 0);
        }

        setupTtsListener();
        buildTargetPhraseList();
    }

    private void buildTargetPhraseList() {
        // Core calls
        targetPhrases.add("Fault");
        targetPhrases.add("Out");
        targetPhrases.add("Let");
        targetPhrases.add("In");

        // MPH Range
        for (int i = EngineConstants.MPH_MIN; i <= EngineConstants.MPH_MAX; i++) {
            targetPhrases.add(i + " miles per hour");
            targetPhrases.add(String.valueOf(i));
        }
    }

    public void initializeCache() {
        // Runs the actual check-and-load pass on a dedicated background thread and
        // returns immediately -- see this class's header comment (2026-07-20 ANR note)
        // for why this can't run inline on the caller's thread (always the main thread
        // in practice, via TextToSpeech.onInit()'s callback).
        new Thread(this::runInitializeCache, "FastSpeechEngine-Init").start();
    }

    private void runInitializeCache() {
        // Capture baseline memory before decoding buffers into the C++ layer
        initialNativeMemory = Debug.getNativeHeapAllocatedSize();
        Log.d(TAG, "Initializing cache. Checking " + targetPhrases.size() + " phrases.");

        for (String phrase : targetPhrases) {
            File targetFile = new File(cacheDir, sanitizeFilename(phrase));

            if (targetFile.exists() && targetFile.length() > 0) {
                // File exists, load directly into memory
                loadIntoSoundPool(phrase, targetFile);
            } else {
                // File missing, command TTS to synthesize it asynchronously
                synthesizePhrase(phrase, targetFile);
            }
        }
    }

    private void synthesizePhrase(String phrase, File targetFile) {
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, EngineConstants.UTT_PREFIX + phrase);

        // This command returns immediately; rendering happens on a background thread
        int result = tts.synthesizeToFile(phrase, params, targetFile, EngineConstants.UTT_PREFIX + phrase);
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Failed to queue synthesis for: " + phrase);
        }
    }

    private void setupTtsListener() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) { }

            @Override
            public void onDone(String utteranceId) {
                if (utteranceId != null && utteranceId.startsWith(EngineConstants.UTT_PREFIX)) {
                    String phrase = utteranceId.substring(EngineConstants.UTT_PREFIX.length());
                    File completedFile = new File(cacheDir, sanitizeFilename(phrase));

                    if (completedFile.exists()) {
                        // Load the newly minted file. SoundPool handles file I/O on its own background thread.
                        loadIntoSoundPool(phrase, completedFile);
                    }
                }
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "TTS Synthesis failed for utterance: " + utteranceId);
            }
        });

        // Setup listener to track when SoundPool finishes decoding the PCM buffer
        soundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (status == 0) {
                // Periodically check memory growth as files load
                printNativeMemoryFootprint();
            } else {
                Log.e(TAG, "SoundPool failed to decode buffer for ID: " + sampleId);
            }
        });
    }

    private void loadIntoSoundPool(String phrase, File file) {
        // The '1' is the priority parameter, maintained for future Android compatibility
        int soundId = soundPool.load(file.getAbsolutePath(), 1);
        soundMap.put(phrase, soundId);
    }

    public void speak(String text) {
        if (soundMap.containsKey(text)) {
            Integer soundId = soundMap.get(text);
            if (soundId != null) {
                // Play immediately from native RAM
                // play(soundID, leftVolume, rightVolume, priority, loop, rate)
                soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f);
                return;
            }
        }

        // Algorithmic Fallback: Phrase not in cache or still synthesizing
        Log.w(TAG, "Cache miss for: '" + text + "'. Falling back to dynamic TTS.");
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private String sanitizeFilename(String phrase) {
        return phrase.toLowerCase().replace(" ", "_") + ".wav";
    }

    public void printNativeMemoryFootprint() {
        long currentNativeMemory = Debug.getNativeHeapAllocatedSize();
        long diffBytes = currentNativeMemory - initialNativeMemory;
        double diffMb = diffBytes / (1024.0 * 1024.0);
        Log.i(TAG, String.format("Native Heap Audio Overhead: %.2f MB", diffMb));
    }

    public void shutdown() {
        soundPool.release();
        soundMap.clear();
    }
}