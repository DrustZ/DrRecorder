package ai.kitt.snowboy.audio;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import ai.kitt.snowboy.Constants;
import ai.kitt.snowboy.Demo;
import ai.kitt.snowboy.MsgEnum;
import ai.kitt.snowboy.demo.R;
import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineException;
import ai.picovoice.porcupinemanager.PorcupineManagerException;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.mobileconnectors.s3.transferutility.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;

public class AudioService extends Service{
    static String strLog = null;
    Boolean isRecording = Boolean.FALSE;
    Boolean keywordDetected = Boolean.FALSE;
    int conversationStarted = 0; // count for after-trigger minutes
    int recordBtn_pressed_count = 0; // when user press record actively, maximum 5 minutes

    String currentPath = "";
    private LinkedList<String> mFileQueue = new LinkedList<String>();
    private LinkedList<Boolean> mFileStatusQueue = new LinkedList<Boolean>();

    private int mInterval = 10000; // 5 seconds by default, can be changed later
    private Handler mHandler;
    private int preVolume = -1;
    private static long activeTimes = 0;

    PowerManager.WakeLock wakeLock;
    private RecordingThread recordingThread;
    private final IBinder mBinder = new LocalBinder();
    Demo activity;

    private AudioRecorder audioRecorder;
    private Porcupine porcupine;
    private AudioConsumer audioConsumer;
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        setProperVolume();
        activeTimes = 0;
        mHandler = new Handler();

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MyWakelockTag");
        wakeLock.acquire();

        return Service.START_STICKY;
    }

    Runnable mStatusChecker = new Runnable() {
        @Override
        public void run() {
            try {
                if (isRecording) {
                    stopRecording(); //this function can change value of mInterval.
                    mFileQueue.add(currentPath);
                    File fl = new File(currentPath);
                    int file_size = Integer.parseInt(String.valueOf(fl.length()/1024));
//                    Log.d("[Log]", "file "+currentPath+" size: "+String.valueOf(file_size));
//                    Log.d("[Log]", "record stop. Now queue size:" + String.valueOf(mFileQueue.size()));

                    //if active recording mode
                    if (recordBtn_pressed_count > 0) {
                        mFileStatusQueue.add(true);
//                        Log.d("[Log]", "Active recording");
                        conversationStarted = 0;
                        keywordDetected = false;
                        recordBtn_pressed_count -= 1;

                        // rename
                        String fn = mFileQueue.getLast();
                        File from = new File(fn);
                        String fnto = Constants.DEFAULT_WORK_SPACE + "/" + "active_" + fn.substring(fn.lastIndexOf("/")+1);
                        File to = new File(fnto);
                        if(from.exists())
                            from.renameTo(to);
                        mFileQueue.removeLast();
                        mFileQueue.add(fnto);

                        if (recordBtn_pressed_count == 0){
                            //stop recording actively (update UI)
                            activity.StopActiveRecording();
                        }
                    }
                    //else if passive recording mode
                    else {
                        if (conversationStarted > 0) {
                            //upload the prior three conversation
//                            Log.d("[Log]", "conversation file :"+String.valueOf(conversationStarted));
                            conversationStarted -= 1;
                            keywordDetected = false;
                            mFileStatusQueue.add(true);
                        }
                        // keyword recognized
                        else if (keywordDetected) {
                            conversationStarted = 2;
                            keywordDetected = false;
                            if (mFileStatusQueue.size() > 0) {
                                mFileStatusQueue.removeLast();
                                mFileStatusQueue.add(true);
                            }
                            mFileStatusQueue.add(true);
//                            Log.d("[Log]", "file detected!!!");
                        } else {
                            mFileStatusQueue.add(false);
                        }
                    }

                    //to wav
                    String fname = mFileQueue.getLast();
                    ToWavThread ttd = new ToWavThread(fname);
                    ttd.start();
                    mFileQueue.removeLast();
                    mFileQueue.add(fname.replace("pcm", "wav"));
                    Log.d("[Log]", "file quue: "+mFileQueue.getLast());

                    if (mFileQueue.size() > 5) {
                        String uploadPath = mFileQueue.remove();

                        boolean upload = mFileStatusQueue.remove();
                        if (upload) {
                            UploadThread utd = new UploadThread(uploadPath);
                            utd.start();
                        } else {
                            File file = new File(uploadPath);
                            file.delete();
                            Log.d("[Log]","Delete: "+uploadPath);
                        }
                    }
                }
                startRecording();
            } finally {
                mHandler.postDelayed(mStatusChecker, mInterval);
            }
        }
    };

    //returns the instance of the service
    public class LocalBinder extends Binder {
        public AudioService getServiceInstance(){
            return AudioService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public interface Callbacks{
        public void updateClient(long data);
    }

    //Here Activity register to the service as Callbacks client
    public void registerClient(Demo activity){
        this.activity = activity;
    }

    public void StartRunning(){
        AWSMobileClient.getInstance().initialize(this.activity).execute();
        SharedPreferences prefs = getSharedPreferences(Constants.MY_PREFERENCE, MODE_PRIVATE);
        String modelname = Constants.DEFAULT_WORK_SPACE + "hey_google_android.ppn";
        if (prefs.getInt("currentmodel", 0) == 1){
            modelname = Constants.DEFAULT_WORK_SPACE + "ok_google_android.ppn";
        }
        float sensitivity =  prefs.getFloat("sensitivity", 0.5f);
        try {
            porcupine = new Porcupine(Constants.DEFAULT_WORK_SPACE+"porcupine_params.pv", modelname, sensitivity);
        } catch (PorcupineException e) {
            e.printStackTrace();
        }
        audioConsumer = new PorcupineAudioConsumer(new KeywordCallback() {
            @Override
            public void kwDetected(int keyword_index) {
                keywordDetected = true;
                Log.d("[Log]"," ----> Detected !!!" );
                activity.LogTriggerWord(String.format("keyword triggered at " + DateFormat.getDateTimeInstance().format(new Date())));
            }
        });
        audioRecorder = new AudioRecorder(audioConsumer);
        mStatusChecker.run();
//        startRecording();
    }

    private void startRecording() {

        isRecording = true;
        currentPath = getOutputFile();

        audioRecorder.setDataListener(new AudioDataSaver(currentPath));

        try {
            audioRecorder.start();
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    private void stopRecording() {
        isRecording = false;
        try {
            audioRecorder.stop();
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    public void StartActiveRecording(){
        recordBtn_pressed_count = 5;
    }

    public void StopActiveRecording(){
        recordBtn_pressed_count =  1;
    }

    public void DeleteLastTenMin(){
        for (String fn : mFileQueue){
            File file = new File(fn);
            if (file.exists()) {
                file.delete();
            }
        }
        mFileQueue.clear();
        mFileStatusQueue.clear();
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat
                    ("MMdd_HH_mm_ss", Locale.US);
            String filename = Constants.DEFAULT_WORK_SPACE + "/DeleteLog.txt";
            FileWriter fw = new FileWriter(filename, true);
            fw.write( dateFormat.format(new Date()) + "\n");
            fw.close();

            TransferUtility transferUtility =
                    TransferUtility.builder()
                            .context(getApplicationContext())
                            .awsConfiguration(AWSMobileClient.getInstance().getConfiguration())
                            .s3Client(new AmazonS3Client(AWSMobileClient.getInstance().getCredentialsProvider()))
                            .build();

            SharedPreferences prefs = getSharedPreferences(Constants.MY_PREFERENCE, MODE_PRIVATE);
            String prefix = prefs.getString("prefix", null);
            if (prefix == null)
                prefix = "test";

            String uploadname = prefix + "/DeleteLog.txt";
            TransferObserver uploadObserver =
                    transferUtility.upload(
                            uploadname,
                            new File(filename));

        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    private void setProperVolume() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        preVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
//        Log.d("[Log]", " ----> preVolume = "+preVolume);
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
//        Log.d("[Log]"," ----> maxVolume = " + maxVolume);
        int properVolume = (int) ((float) maxVolume * 0.2);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, properVolume, 0);
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
//        Log.d("[Log]"," ----> currentVolume = "+currentVolume);
    }

    private void restoreVolume() {
        if(preVolume>=0) {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, preVolume, 0);
//            Log.d("[Log]"," ----> set preVolume = "+preVolume);
            int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
//            Log.d("[Log]"," ----> currentVolume = "+currentVolume);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRecording();
        restoreVolume();
        if (porcupine != null){
            porcupine.delete();
        }
        mHandler.removeCallbacks(mStatusChecker);
        wakeLock.release();
    }

    private String getOutputFile() {
        SimpleDateFormat dateFormat = new SimpleDateFormat
                ("MMdd_HH_mm_ss", Locale.US);
        String fn = Constants.DEFAULT_WORK_SPACE + "/"
                + dateFormat.format(new Date())
                + ".pcm";
        return fn;
    }

    private void rawToWave(String rawfn, String wavefn) throws IOException {
        File rawFile = new File(rawfn);
        File waveFile = new File(wavefn);
        byte[] rawData = new byte[(int) rawFile.length()];
        DataInputStream input = null;
        try {
            input = new DataInputStream(new FileInputStream(rawFile));
            input.read(rawData);
        } finally {
            if (input != null) {
                input.close();
            }
        }

        DataOutputStream output = null;
        try {
            output = new DataOutputStream(new FileOutputStream(waveFile));
            // WAVE header
            // see http://ccrma.stanford.edu/courses/422/projects/WaveFormat/
            writeString(output, "RIFF"); // chunk id
            writeInt(output, 36 + rawData.length); // chunk size
            writeString(output, "WAVE"); // format
            writeString(output, "fmt "); // subchunk 1 id
            writeInt(output, 16); // subchunk 1 size
            writeShort(output, (short) 1); // audio format (1 = PCM)
            writeShort(output, (short) 1); // number of channels
            writeInt(output, Constants.SAMPLE_RATE); // sample rate
            writeInt(output, Constants.SAMPLE_RATE * 2); // byte rate
            writeShort(output, (short) 2); // block align
            writeShort(output, (short) 16); // bits per sample
            writeString(output, "data"); // subchunk 2 id
            writeInt(output, rawData.length); // subchunk 2 size
            // Audio data (conversion big endian -> little endian)
            short[] shorts = new short[rawData.length / 2];
            ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
            ByteBuffer bytes = ByteBuffer.allocate(shorts.length * 2);
            for (short s : shorts) {
                bytes.putShort(s);
            }

            output.write(fullyReadFileToBytes(rawFile));
        } finally {
            if (output != null) {
                output.close();
            }
            File file = new File(rawfn);
            file.delete();
            Log.d("[Log]", "rawToWave: delete the raw file.");
        }
    }
    byte[] fullyReadFileToBytes(File f) throws IOException {
        int size = (int) f.length();
        byte bytes[] = new byte[size];
        byte tmpBuff[] = new byte[size];
        FileInputStream fis= new FileInputStream(f);
        try {

            int read = fis.read(bytes, 0, size);
            if (read < size) {
                int remain = size - read;
                while (remain > 0) {
                    read = fis.read(tmpBuff, 0, remain);
                    System.arraycopy(tmpBuff, 0, bytes, size - remain, read);
                    remain -= read;
                }
            }
        }  catch (IOException e){
            throw e;
        } finally {
            fis.close();
        }

        return bytes;
    }
    private void writeInt(final DataOutputStream output, final int value) throws IOException {
        output.write(value >> 0);
        output.write(value >> 8);
        output.write(value >> 16);
        output.write(value >> 24);
    }

    private void writeShort(final DataOutputStream output, final short value) throws IOException {
        output.write(value >> 0);
        output.write(value >> 8);
    }

    private void writeString(final DataOutputStream output, final String value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            output.write(value.charAt(i));
        }
    }

    //upload
    public void uploadWithTransferUtility(final String fn) {

        TransferUtility transferUtility =
                TransferUtility.builder()
                        .context(getApplicationContext())
                        .awsConfiguration(AWSMobileClient.getInstance().getConfiguration())
                        .s3Client(new AmazonS3Client(AWSMobileClient.getInstance().getCredentialsProvider()))
                        .build();

        SharedPreferences prefs = getSharedPreferences(Constants.MY_PREFERENCE, MODE_PRIVATE);
        String prefix = prefs.getString("prefix", null);
        if (prefix == null)
            prefix = "test";

        String uploadname = prefix + "/" + prefix + "_" + fn.substring(fn.lastIndexOf("/")+1);
        TransferObserver uploadObserver =
                transferUtility.upload(
                        uploadname,
                        new File(fn));

        // Attach a listener to the observer to get state update and progress notifications
        uploadObserver.setTransferListener(new TransferListener() {

            @Override
            public void onStateChanged(int id, TransferState state) {
                if (TransferState.COMPLETED == state || TransferState.FAILED == state) {
                    // Handle a completed upload.
//                    File file = new File(fn);
//                    file.delete();
//                    Log.d("Log", "Upload finished!");
                }
            }

            @Override
            public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
                float percentDonef = ((float) bytesCurrent / (float) bytesTotal) * 100;
                int percentDone = (int)percentDonef;

//                Log.d("YourActivity", "ID:" + id + " bytesCurrent: " + bytesCurrent
//                        + " bytesTotal: " + bytesTotal + " " + percentDone + "%");
            }

            @Override
            public void onError(int id, Exception ex) {
                // Handle errors
                ex.printStackTrace();
//                File file = new File(fn);
//                file.delete();
            }

        });
    }

    class ToWavThread extends Thread {
        String fname;
        public ToWavThread(String fname) {
            this.fname = fname;
        }

        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);
            String wavfn = this.fname.replace("pcm", "wav");
            try {
                rawToWave(this.fname, wavfn);
            }catch (Exception e) {
                e.printStackTrace();
                File file = new File(this.fname);
                file.delete();
            }
        }
    }

    class UploadThread extends Thread {
        String fname;
        public UploadThread(String fname) {
            this.fname = fname;
        }

        public void run() {
            uploadWithTransferUtility(this.fname);
        }
    };

    /**
     * PorcupineAudioConsumer process the raw PCM data returned by {@link AudioRecorder} and
     * notifies the user by using {@link KeywordCallback}.
     */
    public class PorcupineAudioConsumer implements AudioConsumer {
        private final KeywordCallback keywordCallback;

        /**
         * Initialize PorcupineAudioConsumer.
         * @param keywordCallback Callback to use when the keyword is detected.
         */
        PorcupineAudioConsumer(KeywordCallback keywordCallback) {
            this.keywordCallback = keywordCallback;
        }
        /**
         * Consume the raw PCM data and notify user by using {@link KeywordCallback}.
         * @throws PorcupineManagerException An exception is thrown if there is an error while processing
         * the PCM data by Porcupine library.
         */
        @Override
        public void consume(short[] pcm) throws PorcupineManagerException {
            try {
                final int keyword_index = porcupine.processFrameMultipleKeywords(pcm);
                if (keyword_index >= 0) {
                    keywordCallback.kwDetected(keyword_index);
                }
            } catch (PorcupineException e) {
                throw new PorcupineManagerException(e);
            }
        }

        /**
         * Number of audio samples per frame.
         * @return Number of samples per frame.
         */
        @Override
        public int getFrameLength() {
            return porcupine.getFrameLength();
        }

        /**
         * Number of audio samples per second.
         * @return Sample rate of the audio data.
         */
        @Override
        public int getSampleRate() {
            return porcupine.getSampleRate();
        }
    }
}
