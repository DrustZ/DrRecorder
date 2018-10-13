package ai.kitt.snowboy;

import ai.kitt.snowboy.audio.AudioService;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context;

import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.mobile.client.AWSStartupHandler;
import com.amazonaws.mobile.client.AWSStartupResult;
import com.amazonaws.mobileconnectors.pinpoint.PinpointConfiguration;
import com.amazonaws.mobileconnectors.pinpoint.PinpointManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ai.kitt.snowboy.demo.R;
import ai.picovoice.porcupinemanager.KeywordCallback;
import ai.picovoice.porcupinemanager.PorcupineManager;
import ai.picovoice.porcupinemanager.PorcupineManagerException;

public class Demo extends Activity {
    public static PinpointManager pinpointManager;
    private Button record_button;
    private Button play_button;
    private Button prefix_button;
    private Button active_recording_button;
    private Button delete_button;
    private Button admin_button;
    private LinearLayout l_layout;
    private RelativeLayout r_layout;
    private TextView log_view;

    private EditText editText;
    static String strLog = null;
    private int preVolume = -1;
    Intent serviceIntent;
    Boolean record_started = false;
    Boolean is_active_recording = false;
    long lastDown = 0;
    AudioService audioservice ;
    AlertDialog.Builder builder = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        serviceIntent = new Intent(Demo.this, AudioService.class);
        setContentView(R.layout.main);
        setUI();

        AWSMobileClient.getInstance().initialize(this, new AWSStartupHandler() {
            @Override
            public void onComplete(AWSStartupResult awsStartupResult) {
                Log.d("YourMainActivity", "AWSMobileClient is instantiated and you are connected to AWS!");
                startRecording();
            }
        }).execute();

//        AppResCopy.copyResFromAssetsToSD(this);

//        Thread.setDefaultUncaughtExceptionHandler(new MyExceptionHandler(this));

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

    }

    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
//            Toast.makeText(Demo.this, "onServiceConnected called", Toast.LENGTH_SHORT).show();
            // We've binded to LocalService, cast the IBinder and get LocalService instance
            AudioService.LocalBinder binder = (AudioService.LocalBinder) service;
            audioservice = binder.getServiceInstance(); //Get instance of your service!
            audioservice.registerClient(Demo.this); //Activity register in the service as client for callabcks!
            audioservice.StartRunning(); // begin function
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
//            Toast.makeText(Demo.this, "onServiceDisconnected called", Toast.LENGTH_SHORT).show();
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

        admin_button = (Button) findViewById(R.id.adminTriggerBtn);
        admin_button.setOnTouchListener(admin_button_handle);

        l_layout = (LinearLayout) findViewById(R.id.linearlayout);
        r_layout = (RelativeLayout) findViewById(R.id.titleLinearLayout);
        l_layout.setVisibility(View.GONE);
        r_layout.setVisibility(View.GONE);

        log_view = (TextView) findViewById(R.id.logView);
        log_view.setMovementMethod(new ScrollingMovementMethod());

        editText = (EditText) findViewById(R.id.prefixEdit);
        prefix_button = (Button) findViewById(R.id.prefixBtn);
        prefix_button.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view) {
                builder = new AlertDialog.Builder(Demo.this);
                builder.setTitle("Please enter pin:");

                // Set up the input
                final EditText input = new EditText(Demo.this);
                // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                builder.setView(input);

                // Set up the buttons
                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String text = input.getText().toString();
                        InputMethodManager mgr = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        mgr.hideSoftInputFromWindow(input.getWindowToken(), 0);
                        if (text.equals("2050")){
                            Toast.makeText(Demo.this, "Prefix Changed Successfully.", Toast.LENGTH_SHORT).show();
                            String prefix = editText.getText().toString();
                            prefix = prefix.trim();
                            if (!prefix.isEmpty()){
                                SharedPreferences.Editor editor = getSharedPreferences(Constants.MY_PREFERENCE, MODE_PRIVATE).edit();
                                editor.putString("prefix", prefix);
                                editor.apply();
                            }
                        }
                    }
                });
                builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });
                builder.show();

                editText.clearFocus();
            }
        });

        active_recording_button = (Button) findViewById(R.id.ActiveRecordBtn);
        active_recording_button.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view) {
                if (is_active_recording){
                    is_active_recording = false;
                    active_recording_button.setTextColor(Color.parseColor("#EA2929"));
                    active_recording_button.setBackgroundResource(R.drawable.btnborder);
                    active_recording_button.setText("Start Recording");
                    audioservice.StopActiveRecording();
                } else {
                    if (audioservice != null) {
                        is_active_recording = true;
                        active_recording_button.setTextColor(Color.WHITE);
                        active_recording_button.setBackgroundColor(Color.parseColor("#EA2929"));
                        active_recording_button.setText("Stop Recording");
                        audioservice.StartActiveRecording();
                    }
                }
            }
        });
        delete_button = (Button) findViewById(R.id.btnDelete);
        delete_button.setOnClickListener(delete_button_handle);

        SharedPreferences prefs = getSharedPreferences(Constants.MY_PREFERENCE, MODE_PRIVATE);
        String prefix = prefs.getString("prefix", null);
        if (prefix != null)
            editText.setText(prefix);
    }
    
    private void setMaxVolume() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        preVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0);
    }

    private void startRecording() {
        record_started = true;
        startService(serviceIntent);
        bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE); //Binding to the service!
        record_button.setText(R.string.btn1_stop);
    }

    private void stopRecording() {
        record_started = false;
        unbindService(mConnection);
        stopService(serviceIntent);
        record_button.setText(R.string.btn1_start);
    }

    public void StopActiveRecording() {
        is_active_recording = false;
        active_recording_button.setTextColor(Color.parseColor("#EA2929"));
        active_recording_button.setBackgroundResource(R.drawable.btnborder);
        active_recording_button.setText("Start Recording");
    }

    public void LogTriggerWord(final String s){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                log_view.setText(log_view.getText()+"\n"+s);
                final int scrollAmount = log_view.getLayout().getLineTop(log_view.getLineCount()) - log_view.getHeight();
                if (scrollAmount > 0) {
                    log_view.scrollTo(0, scrollAmount);
                }
            }
        });
    }

    private void signOut() {
    }

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
                signOut();
            }
        }
    };

    private OnClickListener delete_button_handle = new OnClickListener() {
        // @Override
        public void onClick(View arg0) {
            builder = new AlertDialog.Builder(Demo.this);
            builder.setTitle("Please enter pin:");

            // Set up the input
            final EditText input = new EditText(Demo.this);
            // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            builder.setView(input);

            // Set up the buttons
            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String text = input.getText().toString();
                    InputMethodManager mgr = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    mgr.hideSoftInputFromWindow(input.getWindowToken(), 0);
                    if (text.equals("1900")){
                        Toast.makeText(Demo.this, "Deleted Successfully.", Toast.LENGTH_SHORT).show();
                        if (audioservice != null) {
                            audioservice.DeleteLastTenMin();
                        }
                    }
                }
            });
            builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });
            builder.show();
        }
    };

    private View.OnTouchListener admin_button_handle = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                lastDown = System.currentTimeMillis();
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
//                Log.d("[Log]", "onTouch: "+String.valueOf(System.currentTimeMillis() - lastDown));
                if (System.currentTimeMillis() - lastDown > 0) {
                    if (l_layout.getVisibility() == View.VISIBLE){
                        l_layout.setVisibility(View.GONE);
                        r_layout.setVisibility(View.GONE);
                        log_view.setVisibility(View.VISIBLE);
                    } else {
                        l_layout.setVisibility(View.VISIBLE);
                        r_layout.setVisibility(View.VISIBLE);
                        log_view.setVisibility(View.GONE);
                    }
                }
            }
            return true;
        }
    };

    @Override
     public void onDestroy() {
        super.onDestroy();
     }
}
