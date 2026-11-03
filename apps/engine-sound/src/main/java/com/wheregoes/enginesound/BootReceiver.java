package com.wheregoes.enginesound;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("EngineSoundBoot", "received: " + intent.getAction());
        if (context.getSharedPreferences(EngineConfig.PREFS, Context.MODE_PRIVATE)
                .getBoolean(EngineConfig.KEY_AUTO, false)) {
            context.startForegroundService(new Intent(context, AutoKeepService.class));
        }
    }
}
