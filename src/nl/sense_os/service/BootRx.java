/*
 ************************************************************************************************************
 *     Copyright (C)  2010 Sense Observation Systems, Rotterdam, the Netherlands.  All rights reserved.     *
 ************************************************************************************************************
 */
package nl.sense_os.service;

import nl.sense_os.service.SensePrefs.Status;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootRx extends BroadcastReceiver {

    private static final String TAG = "Sense Boot Receiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences statusPrefs = context.getSharedPreferences(SensePrefs.STATUS_PREFS,
                Context.MODE_PRIVATE);
        final boolean autostart = statusPrefs.getBoolean(Status.AUTOSTART, false);

        // automatically start the Sense service if this is set in the preferences
        if (true == autostart) {
            Log.i(TAG, "Autostart Sense Platform service");
            Intent startService = new Intent("nl.sense_os.service.ISenseService");
            ComponentName service = context.startService(startService);
            if (null == service) {
                Log.w(TAG, "Failed to start Sense Platform service");
            }
        } else {
            Log.e(TAG, "Sense Platform service should not be started at boot");
        }
    }
}
