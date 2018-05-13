package ai.kitt.snowboy.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import ai.kitt.snowboy.Constants;
import ai.kitt.snowboy.MsgEnum;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import ai.kitt.snowboy.SnowboyDetect;

public class RecordingThread {
    static { System.loadLibrary("snowboy-detect-android"); }

    private static final String TAG = RecordingThread.class.getSimpleName();

    private static final String ACTIVE_RES = Constants.ACTIVE_RES;
    private static final String ACTIVE_UMDL_ALEXA = Constants.ACTIVE_UMDL_ALEXA;
    private static final String ACTIVE_UMDL_GOOGLE = Constants.ACTIVE_UMDL_GOOGLE;
    
    private volatile boolean shouldContinue, isRecording;
    private AudioDataReceivedListener listener = null;
    private Handler handler = null;
    private Thread thread;
    private AudioRecord record = null;

    private static String strEnvWorkSpace = Constants.DEFAULT_WORK_SPACE;
    private String activeModel_alexa = strEnvWorkSpace+ACTIVE_UMDL_ALEXA;
    private String activeModel_google = strEnvWorkSpace+ACTIVE_UMDL_GOOGLE;
    private String commonRes = strEnvWorkSpace+ACTIVE_RES;   
    
    private SnowboyDetect detector = new SnowboyDetect(commonRes, activeModel_alexa);
    private MediaPlayer player = new MediaPlayer();

    public RecordingThread(Handler handler, AudioDataReceivedListener listener) {
        this.handler = handler;
        this.listener = listener;

        detector.SetSensitivity("0.6"); //0.505 for google
        detector.SetAudioGain(1);
        detector.ApplyFrontend(true);
        try {
            player.setDataSource(strEnvWorkSpace+"ding.wav");
            player.prepare();
        } catch (IOException e) {
//            Log.e(TAG, "Playing ding sound error", e);
        }
    }

    private void sendMessage(MsgEnum what, Object obj){
        if (null != handler) {
            Message msg = handler.obtainMessage(what.ordinal(), obj);
            handler.sendMessage(msg);
        }
    }

    public void startRecording() {
        if (thread != null)
            return;

        shouldContinue = true;
        while(isRecording){
            SystemClock.sleep(5);
        }
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                record();
            }
        });
        thread.start();
    }

    public void stopRecording() {
        if (thread == null)
            return;

        shouldContinue = false;

        while (isRecording){
            SystemClock.sleep(10);
//            Log.v(TAG, "stopRecording: waiting");
        }

        thread.interrupt();
        record.stop();
        record.release();
        record = null;
        if (null != listener) {
            listener.stop();
        }

        thread = null;
    }

    private void record() {
//        Log.v(TAG, "Start");
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        // Buffer size in bytes: for 0.1 second of audio
        int bufferSize = (int)(Constants.SAMPLE_RATE * 0.1 * 2);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = Constants.SAMPLE_RATE * 2;
        }
        isRecording = true;

        byte[] audioBuffer = new byte[bufferSize];
        record = new AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            Constants.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize);

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
//            Log.e(TAG, "Audio Record can't initialize!");
            return;
        }

        record.startRecording();

        if (null != listener) {
            listener.start();
        }
//        Log.v(TAG, "Start recording");

        long shortsRead = 0;
        detector.Reset();
        while (shouldContinue) {
            record.read(audioBuffer, 0, audioBuffer.length);

            if (null != listener) {
                listener.onAudioDataReceived(audioBuffer, audioBuffer.length);
            }
            
            // Converts to short array.
            short[] audioData = new short[audioBuffer.length / 2];
            ByteBuffer.wrap(audioBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(audioData);

            shortsRead += audioData.length;

            if (!shouldContinue) break;

            // Snowboy hotword detection.
            int result = detector.RunDetection(audioData, audioData.length);

            if (result == -2) {
                // post a higher CPU usage:
                // sendMessage(MsgEnum.MSG_VAD_NOSPEECH, null);
            } else if (result == -1) {
                sendMessage(MsgEnum.MSG_ERROR, "Unknown Detection Error");
            } else if (result == 0) {
                // post a higher CPU usage:
                // sendMessage(MsgEnum.MSG_VAD_SPEECH, null);
            } else if (result > 0) {
                sendMessage(MsgEnum.MSG_ACTIVE, null);
//                Log.i("[Log] Snowboy: ", "Hotword " + Integer.toString(result) + " detected!");
                player.start();
            }
        }
        isRecording = false;
//        Log.v(TAG, String.format("Recording stopped. Samples read: %d", shortsRead));
    }
}
