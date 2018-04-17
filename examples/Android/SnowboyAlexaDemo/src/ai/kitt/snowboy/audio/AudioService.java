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
import android.os.SystemClock;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.mobileconnectors.s3.transferutility.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;

public class AudioService extends Service {
    static String strLog = null;
    Boolean isRecording = Boolean.FALSE;
    Boolean keywordDetected = Boolean.FALSE;
    int conversationStarted = 0; // count for after-trigger minutes
    int recordBtn_pressed_count = 0; // when user press record actively, maximum 5 minutes

    String currentPath = "";
    String uploadpath = "";
    private LinkedList<String> mFileQueue = new LinkedList<String>();
    private LinkedList<Boolean> mFileStatusQueue = new LinkedList<Boolean>();

    private int mInterval = 60000; // 5 seconds by default, can be changed later
    private Handler mHandler;
    private int preVolume = -1;
    private static long activeTimes = 0;

    PowerManager.WakeLock wakeLock;
    private Runnable runnable;
    private RecordingThread recordingThread;
    private final IBinder mBinder = new LocalBinder();
    Demo activity;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        setProperVolume();
        activeTimes = 0;
        mHandler = new Handler();
        // passive audio upload
//        runnable_passive = new Runnable() {
//            @Override
//            public void run() {
//                //TODO your background code
//                int fsize = mFileQueue.size();
//                List<String> uploadFiles = new ArrayList<String>();
//                //Add the audio before keyword detected
//                if (fsize > 2) {
//                    uploadFiles.add(mFileQueue.get(fsize - 3));
//                }
//                //Add the audio when keyword detected
//                if (fsize > 1) {
//                    uploadFiles.add(mFileQueue.get(fsize - 2));
//                }
//                //Add the audio after keyword detected
//                uploadFiles.add(mFileQueue.getLast());
//                for (String fn : uploadFiles) {
//                    String wavfn = fn.replace("pcm", "wav");
//                    try {
//                        rawToWave(fn, wavfn);
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                    }
//                }
//            }
//        };

        //active audio upload
        runnable = new Runnable() {
            @Override
            public void run() {
                //TODO your background code
                //Add the audio after keyword detected
                 String wavfn = uploadpath.replace("pcm", "wav");
                 try {
                     rawToWave(uploadpath, wavfn);
                 }catch (Exception e) {
                     e.printStackTrace();
                }
            }
        };

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
                    Log.d("[Log]", "record stop. Now queue size:" + String.valueOf(mFileQueue.size()));

                    //if active recording mode
                    if (recordBtn_pressed_count > 0) {
                        mFileStatusQueue.add(true);
                        Log.d("[Log]", "Active recording");
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
                            Log.d("[Log]", "conversation file :"+String.valueOf(conversationStarted));
                            conversationStarted -= 1;
                            keywordDetected = false;
                            mFileStatusQueue.add(true);
                        }
                        // keyword recognized
                        else if (keywordDetected) {
                            conversationStarted = 2;
                            keywordDetected = false;
                            mFileStatusQueue.add(true);
                            Log.d("[Log]", "file detected!!!");
                        } else {
                            mFileStatusQueue.add(false);
                        }
                    }

                    if (mFileQueue.size() > 10) {
                        uploadpath = mFileQueue.remove();
                        boolean upload = mFileStatusQueue.remove();
                        if (upload){
                            AsyncTask.execute(runnable);
                        } else {
                            File file = new File(uploadpath);
                            file.delete();
                        }
                    }
                }
                SystemClock.sleep(50);
                startRecording();
            } finally {
                // 100% guarantee that this always happens, even if
                // your update method throws an exception
                mHandler.postDelayed(mStatusChecker, mInterval);
            }
        }
    };

    public Handler handle = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            MsgEnum message = MsgEnum.getMsgEnum(msg.what);
            switch(message) {
                case MSG_ACTIVE:
//                    activeTimes++;
                    keywordDetected = true;
//                    Log.d("[Log]"," ----> Detected " + activeTimes + " times");
                    // Toast.makeText(Demo.this, "Active "+activeTimes, Toast.LENGTH_SHORT).show();
                    break;
                case MSG_INFO:
                    Log.d("[Log]"," ----> "+message);
                    break;
                case MSG_VAD_SPEECH:
                    Log.d("[Log]"," ----> normal voice");
                    break;
                case MSG_VAD_NOSPEECH:
                    Log.d("[Log]"," ----> no speech");
                    break;
                case MSG_ERROR:
                    Log.d("[Log]"," ----> " + msg.toString());
                    break;
                default:
                    super.handleMessage(msg);
                    break;
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
        mStatusChecker.run();
    }

    private void startRecording() {
        Log.d("[Log]", "startRecording: called!");
        isRecording = true;
        currentPath = getOutputFile();
        recordingThread = new RecordingThread(handle, new AudioDataSaver(currentPath));
        recordingThread.startRecording();
    }

    private void stopRecording() {
        isRecording = false;
        recordingThread.stopRecording();
    }

    public void StartActiveRecording(){
        recordBtn_pressed_count = 5;
    }

    public void StopActiveRecording(){
        recordBtn_pressed_count =  1;
    }

    public void DeleteLastTenMin(){
        mFileQueue.clear();
        mFileStatusQueue.clear();
    }

    private void setProperVolume() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        preVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        Log.d("[Log]", " ----> preVolume = "+preVolume);
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Log.d("[Log]"," ----> maxVolume = " + maxVolume);
        int properVolume = (int) ((float) maxVolume * 0.2);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, properVolume, 0);
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        Log.d("[Log]"," ----> currentVolume = "+currentVolume);
    }

    private void restoreVolume() {
        if(preVolume>=0) {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, preVolume, 0);
            Log.d("[Log]"," ----> set preVolume = "+preVolume);
            int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            Log.d("[Log]"," ----> currentVolume = "+currentVolume);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRecording();
        restoreVolume();
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
                uploadWithTransferUtility(wavefn);
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
            prefix = "";

        String uploadname = "s3Folder/" + prefix + "_" + fn.substring(fn.lastIndexOf("/")+1);
        TransferObserver uploadObserver =
                transferUtility.upload(
                        uploadname,
                        new File(fn));

        // Attach a listener to the observer to get state update and progress notifications
        uploadObserver.setTransferListener(new TransferListener() {

            @Override
            public void onStateChanged(int id, TransferState state) {
                if (TransferState.COMPLETED == state) {
                    // Handle a completed upload.
                    File file = new File(fn);
                    file.delete();
                    Log.d("Log", "Upload finished!");
                }
            }

            @Override
            public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
                float percentDonef = ((float) bytesCurrent / (float) bytesTotal) * 100;
                int percentDone = (int)percentDonef;

                Log.d("YourActivity", "ID:" + id + " bytesCurrent: " + bytesCurrent
                        + " bytesTotal: " + bytesTotal + " " + percentDone + "%");
            }

            @Override
            public void onError(int id, Exception ex) {
                // Handle errors
            }

        });
    }
}
