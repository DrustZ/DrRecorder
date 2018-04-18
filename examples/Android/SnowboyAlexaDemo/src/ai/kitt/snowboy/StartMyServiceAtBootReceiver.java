package ai.kitt.snowboy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import ai.kitt.snowboy.audio.AudioService;

public class StartMyServiceAtBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("[Log]", "onReceive: boot received");
            Intent i = new Intent(context, Demo.class);
            context.startActivity(i);
        }
    }
}
