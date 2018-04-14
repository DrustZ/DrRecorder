package ai.kitt.snowboy;

import ai.kitt.snowboy.audio.RecordingThread;
import ai.kitt.snowboy.audio.PlaybackThread;
import ai.kitt.snowboy.audio.AudioService;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.text.Html;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context;

import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUser;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserDetails;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.GetDetailsHandler;
import com.amazonaws.mobileconnectors.s3.transferutility.*;
import com.amazonaws.mobile.auth.core.IdentityManager;
import com.amazonaws.mobile.auth.core.SignInStateChangeListener;
import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.mobile.client.AWSStartupHandler;
import com.amazonaws.mobile.client.AWSStartupResult;
import com.amazonaws.mobileconnectors.pinpoint.PinpointConfiguration;
import com.amazonaws.mobileconnectors.pinpoint.PinpointManager;

import java.util.HashMap;
import java.util.Map;

import ai.kitt.snowboy.audio.AudioDataSaver;
import ai.kitt.snowboy.demo.R;


public class Demo extends Activity {
    public static PinpointManager pinpointManager;
    private Button record_button;
    private Button play_button;
    private Button prefix_button;
    private EditText editText;
    private TextView log;
    private ScrollView logView;
    static String strLog = null;
    PowerManager.WakeLock wakeLock;
    private int preVolume = -1;
    Intent serviceIntent;
    Boolean record_started = false;
    AudioService audioservice ;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //configure and get AWS connected
        AWSMobileClient.getInstance().initialize(this, new AWSStartupHandler() {
            @Override
            public void onComplete(AWSStartupResult awsStartupResult) {
                Log.d("YourMainActivity", "AWSMobileClient is instantiated and you are connected to AWS!");
            }
        }).execute();

        serviceIntent = new Intent(Demo.this, AudioService.class);
        setContentView(R.layout.main);
        setUI();

        AppResCopy.copyResFromAssetsToSD(this);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MyWakelockTag");
        wakeLock.acquire();

        //AWS analytics service
        PinpointConfiguration pinpointConfig = new PinpointConfiguration(
                getApplicationContext(),
                AWSMobileClient.getInstance().getCredentialsProvider(),
                AWSMobileClient.getInstance().getConfiguration());

        pinpointManager = new PinpointManager(pinpointConfig);

        // Start a session with Pinpoint
        pinpointManager.getSessionClient().startSession();

        // Stop the session and submit the default app started event
        pinpointManager.getSessionClient().stopSession();
        pinpointManager.getAnalyticsClient().submitEvents();

        IdentityManager.getDefaultIdentityManager().addSignInStateChangeListener(new SignInStateChangeListener() {
            @Override
            public void onUserSignedIn() {
                Log.d("[Log]", "User Signed In");

                GetDetailsHandler getDetailsHandler = new GetDetailsHandler() {
                    @Override
                    public void onSuccess(CognitoUserDetails cognitoUserDetails) {
                        // The user detail are in cognitoUserDetails

                        // Fetch the user details
                        Map userAtts=new HashMap();
                        userAtts =cognitoUserDetails.getAttributes().getAttributes();
                        String userName = userAtts.get("alias:preferred_username").toString();
                    }

                    @Override
                    public void onFailure(Exception exception) {
                        // Fetch user details failed, check exception for the cause
                    }
                };
            }

            @Override
            public void onUserSignedOut() {
                Log.d("[Log]", "User Signed Out");
            }
        });
    }

    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            Toast.makeText(Demo.this, "onServiceConnected called", Toast.LENGTH_SHORT).show();
            // We've binded to LocalService, cast the IBinder and get LocalService instance
            AudioService.LocalBinder binder = (AudioService.LocalBinder) service;
            audioservice = binder.getServiceInstance(); //Get instance of your service!
            audioservice.registerClient(Demo.this); //Activity register in the service as client for callabcks!
            audioservice.StartRunning(); // begin function
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Toast.makeText(Demo.this, "onServiceDisconnected called", Toast.LENGTH_SHORT).show();
        }
    };

    void showToast(CharSequence msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
    
    private void setUI() {
        record_button = (Button) findViewById(R.id.btn_test1);
        record_button.setOnClickListener(record_button_handle);
        record_button.setEnabled(true);
        
        play_button = (Button) findViewById(R.id.btn_test2);
        play_button.setOnClickListener(play_button_handle);
        play_button.setEnabled(true);

        log = (TextView)findViewById(R.id.log);
        logView = (ScrollView)findViewById(R.id.logView);

        editText = (EditText) findViewById(R.id.prefixEdit);
        prefix_button = (Button) findViewById(R.id.prefixBtn);
        prefix_button.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view) {
                String prefix = editText.getText().toString();
                prefix = prefix.trim();
                if (!prefix.isEmpty()){
                    SharedPreferences.Editor editor = getSharedPreferences(Constants.MY_PREFERENCE, MODE_PRIVATE).edit();
                    editor.putString("prefix", prefix);
                    editor.apply();
                }
                editText.clearFocus();
            }
        });

        SharedPreferences prefs = getSharedPreferences(Constants.MY_PREFERENCE, MODE_PRIVATE);
        String prefix = prefs.getString("prefix", null);
        if (prefix != null)
            editText.setText(prefix);
    }
    
    private void setMaxVolume() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        preVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        updateLog(" ----> preVolume = "+preVolume, "green");
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        updateLog(" ----> maxVolume = "+maxVolume, "green");
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0);
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        updateLog(" ----> currentVolume = "+currentVolume, "green");
    }

    private void startRecording() {
        record_started = true;
        startService(serviceIntent);
        bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE); //Binding to the service!
        updateLog(" ----> recording started ...", "green");
        record_button.setText(R.string.btn1_stop);
    }

    private void stopRecording() {
        record_started = false;
        unbindService(mConnection);
        stopService(serviceIntent);
        updateLog(" ----> recording stopped ", "green");
        record_button.setText(R.string.btn1_start);
    }

    private void startPlayback() {
        updateLog(" ----> user sign out ...", "green");
        IdentityManager.getDefaultIdentityManager().signOut();
        Intent myIntent = new Intent(Demo.this, AuthenticatorActivity.class);
        Demo.this.startActivity(myIntent);
    }

//
//    private void stopPlayback() {
//        updateLog(" ----> playback stopped ", "green");
//        play_button.setText(R.string.btn2_start);
//
//    }

    private void sleep() {
        try { Thread.sleep(500);
        } catch (Exception e) {}
    }
    
    private OnClickListener record_button_handle = new OnClickListener() {
        // @Override
        public void onClick(View arg0) {
            if(record_button.getText().equals(getResources().getString(R.string.btn1_start))) {
                sleep();
                startRecording();
            } else {
                if (record_started) {
                    stopRecording();
                    sleep();
                }
            }
        }
    };
    
    private OnClickListener play_button_handle = new OnClickListener() {
        // @Override
        public void onClick(View arg0) {
            if (play_button.getText().equals(getResources().getString(R.string.btn2_start))) {
                if (record_started) {
                    stopRecording();
                    sleep();
                }
                startPlayback();
            }
        }
    };

     public void updateLog(final String text) {

         log.post(new Runnable() {
             @Override
             public void run() {
                 if (currLogLineNum >= MAX_LOG_LINE_NUM) {
                     int st = strLog.indexOf("<br>");
                     strLog = strLog.substring(st+4);
                 } else {
                     currLogLineNum++;
                 }
                 String str = "<font color='white'>"+text+"</font>"+"<br>";
                 strLog = (strLog == null || strLog.length() == 0) ? str : strLog + str;
                 log.setText(Html.fromHtml(strLog));
             }
        });
        logView.post(new Runnable() {
            @Override
            public void run() {
                logView.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    static int MAX_LOG_LINE_NUM = 200;
    static int currLogLineNum = 0;

    public void updateLog(final String text, final String color) {
        log.post(new Runnable() {
            @Override
            public void run() {
                if(currLogLineNum>=MAX_LOG_LINE_NUM) {
                    int st = strLog.indexOf("<br>");
                    strLog = strLog.substring(st+4);
                } else {
                    currLogLineNum++;
                }
                String str = "<font color='"+color+"'>"+text+"</font>"+"<br>";
                strLog = (strLog == null || strLog.length() == 0) ? str : strLog + str;
                log.setText(Html.fromHtml(strLog));
            }
        });
        logView.post(new Runnable() {
            @Override
            public void run() {
                logView.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    private void emptyLog() {
        strLog = null;
        log.setText("");
    }

    @Override
     public void onDestroy() {
        super.onDestroy();
        wakeLock.release();
     }
}
