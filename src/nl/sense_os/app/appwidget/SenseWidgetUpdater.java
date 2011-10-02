package nl.sense_os.app.appwidget;

import android.app.IntentService;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.RemoteViews;

import nl.sense_os.app.R;
import nl.sense_os.service.ISenseService;
import nl.sense_os.service.ISenseServiceCallback;
import nl.sense_os.service.SenseStatusCodes;

public class SenseWidgetUpdater extends IntentService {

    /**
     * Service stub for callbacks from the Sense service.
     */
    private class SenseCallback extends ISenseServiceCallback.Stub {

        @Override
        public void statusReport(int status) {
            updateWidgets(status);
        }
    }

    /**
     * Service connection to handle connection with the Sense service. Manages the
     * <code>service</code> field when the service is connected or disconnected.
     */
    private class SenseServiceConn implements ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            // Log.v(TAG, "Bound to Sense Platform service...");
            service = ISenseService.Stub.asInterface(binder);
            checkServiceStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            // Log.v(TAG, "Sense Platform service disconnected...");

            /* this is not called when the service is stopped, only when it is suddenly killed! */
            service = null;
            isBoundOrBinding = false;
            checkServiceStatus();
        }
    }

    private static final String TAG = "Sense Widget Updater";

    public static final String ACTION_UPDATE = "nl.sense_os.app.AppWidgetUpdate";
    private static final String ACTION_START_PHONE_STATE = "nl.sense_os.app.StartPhoneState";
    private static final String ACTION_START_LOCATION = "nl.sense_os.app.StartLocation";
    private static final String ACTION_START_MOTION = "nl.sense_os.app.StartMotion";
    private static final String ACTION_START_AMBIENCE = "nl.sense_os.app.StartAmbience";
    private static final String ACTION_START_DEVICES = "nl.sense_os.app.StartDevices";
    private static final String ACTION_STOP_PHONE_STATE = "nl.sense_os.app.StopPhoneState";
    private static final String ACTION_STOP_LOCATION = "nl.sense_os.app.StopLocation";
    private static final String ACTION_STOP_MOTION = "nl.sense_os.app.StopMotion";
    private static final String ACTION_STOP_AMBIENCE = "nl.sense_os.app.StopAmbience";
    private static final String ACTION_STOP_DEVICES = "nl.sense_os.app.StopDevices";

    private final ISenseServiceCallback callback = new SenseCallback();

    private boolean isBoundOrBinding;
    private ISenseService service;
    private final ServiceConnection serviceConn = new SenseServiceConn();

    public SenseWidgetUpdater() {
        super(TAG);
    }

    /**
     * Binds to the Sense Service, creating it if necessary.
     */
    private void bindToSenseService() {

        // start the service if it was not running already
        if (!isBoundOrBinding) {
            // Log.v(TAG, "Try to bind to Sense Platform service");
            final Intent serviceIntent = new Intent(ISenseService.class.getName());
            isBoundOrBinding = bindService(serviceIntent, serviceConn, 0);
        } else {
            // already bound
        }
    }

    /**
     * Calls {@link ISenseService#getStatus(ISenseServiceCallback)} on the service. This will
     * generate a callback that updates the buttons ToggleButtons showing the service's state.
     */
    private void checkServiceStatus() {
        // Log.v(TAG, "Checking service status..");
        if (null != service) {
            try {
                // request status report
                service.getStatus(callback);
            } catch (final RemoteException e) {
                Log.e(TAG, "Error checking service status. ", e);
            }
        } else {
            // Log.v(TAG, "Not bound to Sense Platform service! Assume it's not running...");
            updateWidgets(0);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Log.v(TAG, "Creating...");
        bindToSenseService();
    }

    @Override
    public void onDestroy() {
        // Log.v(TAG, "Destroying...");
        unbindFromSenseService();
        super.onDestroy();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        String action = intent.getAction();
        if (ACTION_UPDATE.equals(action)) {
            checkServiceStatus();
        } else if (ACTION_START_PHONE_STATE.equals(action)) {
            setPhoneState(true);
        } else if (ACTION_STOP_PHONE_STATE.equals(action)) {
            setPhoneState(false);
        } else if (ACTION_START_LOCATION.equals(action)) {
            setLocation(true);
        } else if (ACTION_STOP_LOCATION.equals(action)) {
            setLocation(false);
        } else if (ACTION_START_MOTION.equals(action)) {
            setMotion(true);
        } else if (ACTION_STOP_MOTION.equals(action)) {
            setMotion(false);
        } else if (ACTION_START_AMBIENCE.equals(action)) {
            setAmbience(true);
        } else if (ACTION_STOP_AMBIENCE.equals(action)) {
            setAmbience(false);
        } else if (ACTION_START_DEVICES.equals(action)) {
            setDevices(true);
        } else if (ACTION_STOP_DEVICES.equals(action)) {
            setDevices(false);
        } else {
            Log.w(TAG, "Unexpected intent action: " + action);
        }
    }

    private void setAmbience(boolean active) {
        if (null != service) {
            try {
                // request status report
                service.toggleAmbience(active);
            } catch (final RemoteException e) {
                Log.e(TAG, "Error setting device proximity sensor status. ", e);
            }
        } else {
            // Log.v(TAG, "Not bound to Sense Platform service! Assume it's not running...");
            updateWidgets(0);
        }
    }

    private void setDevices(boolean active) {
        if (null != service) {
            try {
                // request status report
                service.toggleDeviceProx(active);
            } catch (final RemoteException e) {
                Log.e(TAG, "Error setting device proximity sensor status. ", e);
            }
        } else {
            // Log.v(TAG, "Not bound to Sense Platform service! Assume it's not running...");
            updateWidgets(0);
        }
    }

    private void setLocation(boolean active) {
        if (null != service) {
            try {
                // request status report
                service.toggleLocation(active);
            } catch (final RemoteException e) {
                Log.e(TAG, "Error setting location sensor status. ", e);
            }
        } else {
            // Log.v(TAG, "Not bound to Sense Platform service! Assume it's not running...");
            updateWidgets(0);
        }
    }

    private void setMotion(boolean active) {
        if (null != service) {
            try {
                // request status report
                service.toggleMotion(active);
            } catch (final RemoteException e) {
                Log.e(TAG, "Error setting motion sensor status. ", e);
            }
        } else {
            // Log.v(TAG, "Not bound to Sense Platform service! Assume it's not running...");
            updateWidgets(0);
        }
    }

    private void setPhoneState(boolean active) {
        Log.d(TAG, "Set phone state: " + active);
        if (null != service) {
            try {
                // request status report
                service.togglePhoneState(active);
            } catch (final RemoteException e) {
                Log.e(TAG, "Error setting phone state sensor status. ", e);
            }
        } else {
            // Log.v(TAG, "Not bound to Sense Platform service! Assume it's not running...");
            updateWidgets(0);
        }
    }

    /**
     * Unbinds from the Sense service, resets {@link #service} and {@link #isBoundOrBinding}.
     */
    private void unbindFromSenseService() {

        if ((true == isBoundOrBinding) && (null != serviceConn)) {
            // Log.v(TAG, "Unbind from Sense Platform service");
            unbindService(serviceConn);
        } else {
            // already unbound
        }
        service = null;
        isBoundOrBinding = false;
    }

    private void updateWidgets(int status) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
        ComponentName provider = new ComponentName(this, SenseWidgetProvider.class);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(provider);

        int activeColor = getResources().getColor(android.R.color.black);
        int inactiveColor = getResources().getColor(android.R.color.darker_gray);

        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(getPackageName(), R.layout.widget);

            boolean active = ((status & SenseStatusCodes.PHONESTATE) > 0);
            views.setTextColor(R.id.widget_phone_state_txt, active ? activeColor : inactiveColor);
            views.setInt(R.id.widget_phone_state_btn, "setBackgroundResource",
                    active ? R.drawable.widget_selector_left_active
                            : R.drawable.widget_selector_left_inactive);

            Intent intent = new Intent(active ? ACTION_STOP_PHONE_STATE : ACTION_START_PHONE_STATE);
            PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, 0);
            views.setOnClickPendingIntent(R.id.widget_phone_state_btn, pendingIntent);

            active = ((status & SenseStatusCodes.LOCATION) > 0);
            views.setTextColor(R.id.widget_location_txt, active ? activeColor : inactiveColor);
            views.setInt(R.id.widget_location_btn, "setBackgroundResource",
                    active ? R.drawable.widget_selector_active
                            : R.drawable.widget_selector_inactive);

            intent = new Intent(active ? ACTION_STOP_LOCATION : ACTION_START_LOCATION);
            pendingIntent = PendingIntent.getService(this, 0, intent, 0);
            views.setOnClickPendingIntent(R.id.widget_location_btn, pendingIntent);

            active = ((status & SenseStatusCodes.MOTION) > 0);
            views.setTextColor(R.id.widget_motion_txt, active ? activeColor : inactiveColor);
            views.setInt(R.id.widget_motion_btn, "setBackgroundResource",
                    active ? R.drawable.widget_selector_active
                            : R.drawable.widget_selector_inactive);

            intent = new Intent(active ? ACTION_STOP_MOTION : ACTION_START_MOTION);
            pendingIntent = PendingIntent.getService(this, 0, intent, 0);
            views.setOnClickPendingIntent(R.id.widget_motion_btn, pendingIntent);

            active = ((status & SenseStatusCodes.AMBIENCE) > 0);
            views.setTextColor(R.id.widget_ambience_txt, active ? activeColor : inactiveColor);
            views.setInt(R.id.widget_ambience_btn, "setBackgroundResource",
                    active ? R.drawable.widget_selector_active
                            : R.drawable.widget_selector_inactive);

            intent = new Intent(active ? ACTION_STOP_AMBIENCE : ACTION_START_AMBIENCE);
            pendingIntent = PendingIntent.getService(this, 0, intent, 0);
            views.setOnClickPendingIntent(R.id.widget_ambience_btn, pendingIntent);

            active = ((status & SenseStatusCodes.DEVICE_PROX) > 0);
            views.setTextColor(R.id.widget_devices_txt, active ? activeColor : inactiveColor);
            views.setInt(R.id.widget_devices_btn, "setBackgroundResource",
                    active ? R.drawable.widget_selector_right_active
                            : R.drawable.widget_selector_right_inactive);

            intent = new Intent(active ? ACTION_STOP_DEVICES : ACTION_START_DEVICES);
            pendingIntent = PendingIntent.getService(this, 0, intent, 0);
            views.setOnClickPendingIntent(R.id.widget_devices_btn, pendingIntent);

            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }
}