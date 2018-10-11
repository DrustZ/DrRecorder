package ai.kitt.snowboy.audio;


import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.picovoice.porcupinemanager.PorcupineManagerException;

/**
 * Record the audio data from microphone and pass the raw PCM data to {@link AudioConsumer}.
 */
class AudioRecorder {
    private static final String TAG = AudioRecorder.class.getName();

    private final AudioConsumer audioConsumer;
    private final int sampleRate;
    private final int frameLength;
    private AudioDataReceivedListener listener = null;

    private AtomicBoolean started = new AtomicBoolean(false);
    private AtomicBoolean stop = new AtomicBoolean(false);
    private AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * A task to record audio and send the audio samples to Porcupine library for processing.
     */
    private class RecordTask implements Callable<Void> {
        /**
         * Record audio.
         * @return return null that is needed by the {@link Callable} interface.
         * @throws PorcupineManagerException An exception is thrown if {@link AudioRecord} or
         * {@link ai.picovoice.porcupine} throws an error.
         */
        @Override
        public Void call() throws PorcupineManagerException {
            // Set the priority of this thread.
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            record();
            return null;
        }
    }

    /**
     * Initialize AudioRecorder.
     * @param audioConsumer Consumer for the audio samples recorded by {@link ai.picovoice.porcupinemanager.AudioRecorder}.
     */
    AudioRecorder(AudioConsumer audioConsumer, AudioDataReceivedListener listener) {
        this.audioConsumer = audioConsumer;
        this.sampleRate = audioConsumer.getSampleRate();
        this.frameLength = audioConsumer.getFrameLength();
        this.listener = listener;
    }

    /**
     * Start recording in a worker thread.
     * @throws PorcupineManagerException exception is thrown if the {@link ai.picovoice.porcupinemanager.AudioRecorder.RecordTask} throws an error.
     */
    void start() throws PorcupineManagerException {
        if (started.get()) {
            return;
        }

        started.set(true);
        AudioRecorder.RecordTask recordTask = new AudioRecorder.RecordTask();
        ExecutorService recordExecutor = Executors.newSingleThreadExecutor();
        recordExecutor.submit(recordTask);

    }

    /**
     * Stop the recorder gracefully.
     * @throws InterruptedException if the thread is interrupted.
     */
    void stop() throws InterruptedException{
        if (!started.get()) {
            return;
        }
        stop.set(true);
        while (!stopped.get()) {
            Thread.sleep(10);
        }
        if (null != listener) {
            listener.stop();
        }
        started.set(false);
    }

    /***
     * Record the audio and call the {@link AudioConsumer} to consume the raw PCM data.
     * @throws PorcupineManagerException exception is thrown if {@link AudioConsumer} throws an error or
     * {@link AudioRecord} throws an error.
     */
    private void record() throws PorcupineManagerException {
        int bufferSize = Math.max(sampleRate / 2,
                AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT));

        // use short to hold 16-bit PCM encoding
        short[] buffer = new short[frameLength];
        AudioRecord record = null;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            record.startRecording();
            if (null != listener) {
                listener.start();
            }

            while (!stop.get()) {
                int r = record.read(buffer, 0, buffer.length);

                byte[] audioData = new byte[buffer.length * 2];
                ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer);

                //if there are enough audio samples pass it to the consumer.
                if (r == buffer.length) {
                    audioConsumer.consume(buffer);
                } else {
                    Log.d(TAG, "Not enough samples for the audio consumer.");
                }
                if (null != listener) {
                    listener.onAudioDataReceived(audioData, audioData.length);
                }
            }
            record.stop();

        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new PorcupineManagerException(e);
        } finally {
            if (record != null) {
                record.release();
            }
            stopped.set(true);
        }
    }
}
