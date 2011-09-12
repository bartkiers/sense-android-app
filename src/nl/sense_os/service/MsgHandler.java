/*
 * ***********************************************************************************************************
 * Copyright (C) 2010 Sense Observation Systems, Rotterdam, the Netherlands. All rights reserved. *
 * **
 * ************************************************************************************************
 * *********
 */
package nl.sense_os.service;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map.Entry;

import nl.sense_os.service.SensePrefs.Auth;
import nl.sense_os.service.SensePrefs.Main;
import nl.sense_os.service.SensorData.DataPoint;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

public class MsgHandler extends Service {

    /**
     * Handler for tasks that send buffered sensor data to CommonSense. Nota that this handler is
     * re-usable: every time the handler receives a message, it gets the latest data in a Cursor and
     * sends it to CommonSense.<br>
     * <br>
     * Subclasses have to implement {@link #getUnsentData()} and
     * {@link #onTransmitSuccess(JSONObject)} to make them work with their intended data source.
     */
    private abstract class AbstractDataTransmitHandler extends Handler {

        final Context context;
        String cookie;
        Cursor cursor;
        private WakeLock wakeLock;
        private URL url;
        private final DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ENGLISH);
        private final NumberFormat dateFormatter = new DecimalFormat("##########.###", symbols);

        public AbstractDataTransmitHandler(Context context, Looper looper) {
            super(looper);
            this.context = context;
            try {
                url = new URL(SenseUrls.SENSOR_DATA.replace("/<id>/", "/"));
            } catch (MalformedURLException e) {
                // should never happen
                Log.e(TAG, "Failed to create the URL to post sensor data points to");
            }
        }

        /**
         * Cleans up after transmission is over. Closes the Cursor with the data and releases the
         * wake lock. Should always be called after transmission, even if the attempt failed.
         */
        private void cleanup() {
            if (null != cursor) {
                cursor.close();
                cursor = null;
            }
            if (null != wakeLock) {
                wakeLock.release();
                wakeLock = null;
            }
        }

        /**
         * @return Cursor with the data points that have to be sent to CommonSense.
         */
        protected abstract Cursor getUnsentData();

        @Override
        public void handleMessage(Message msg) {
            try {
                cookie = msg.getData().getString("cookie");
                cursor = getUnsentData();
                if (cursor.getCount() > 0) {
                    transmit();
                } else {
                    // nothing to transmit
                }
            } catch (Exception e) {
                if (null != e.getMessage()) {
                    Log.e(TAG, "Exception sending buffered data: " + e.getMessage()
                            + " Data will be resent later.");
                } else {
                    Log.e(TAG, "Exception sending cursor data. Data will be resent later.", e);
                }

            } finally {
                cleanup();
            }
        }

        /**
         * Performs cleanup tasks after transmission was successfully completed. Should update the
         * data point records to show that they have been sent to CommonSense.
         * 
         * @param transmission
         *            The JSON Object that was sent to CommonSense. Contains all the data points
         *            that were transmitted.
         * @throws JSONException
         */
        protected abstract void onTransmitSuccess(JSONObject transmission) throws JSONException;

        /**
         * Transmits the data points from {@link #cursor} to CommonSense. Any "file" type data
         * points will be sent separately via
         * {@link MsgHandler#sendSensorData(String, JSONObject, String, String)}.
         * 
         * @throws JSONException
         * @throws MalformedURLException
         */
        private void transmit() throws JSONException, MalformedURLException {

            // make sure the device stays awake while transmitting
            wakeLock = getWakeLock();
            wakeLock.acquire();

            // continue until all points in the cursor have been sent
            HashMap<String, JSONObject> sensorDataMap = null;
            while (!cursor.isAfterLast()) {

                // organize the data into a hash map sorted by sensor
                sensorDataMap = new HashMap<String, JSONObject>();
                String sensorName, sensorDesc, dataType, value;
                long timestamp;
                int points = 0;
                while (points < MAX_POST_DATA && !cursor.isAfterLast()) {

                    // get the data point details
                    sensorName = cursor.getString(cursor.getColumnIndex(DataPoint.SENSOR_NAME));
                    sensorDesc = cursor.getString(cursor
                            .getColumnIndex(DataPoint.SENSOR_DESCRIPTION));
                    dataType = cursor.getString(cursor.getColumnIndex(DataPoint.DATA_TYPE));
                    value = cursor.getString(cursor.getColumnIndex(DataPoint.VALUE));
                    timestamp = cursor.getLong(cursor.getColumnIndex(DataPoint.TIMESTAMP));

                    // "normal" data is added to the map until we reach the max amount of points
                    if (!dataType.equals(SenseDataTypes.FILE)) {

                        // construct JSON representation of the value
                        JSONObject jsonDataPoint = new JSONObject();
                        jsonDataPoint.put("date", dateFormatter.format(timestamp / 1000d));
                        jsonDataPoint.put("value", value);

                        // put the new value Object in the appropriate sensor's data
                        String key = sensorName + sensorDesc;
                        JSONObject sensorEntry = sensorDataMap.get(key);
                        JSONArray data = null;
                        if (sensorEntry == null) {
                            sensorEntry = new JSONObject();
                            sensorEntry.put("sensor_id", SenseApi.getSensorId(context, sensorName,
                                    value, dataType, sensorDesc));
                            data = new JSONArray();
                        } else {
                            data = sensorEntry.getJSONArray("data");
                        }
                        data.put(jsonDataPoint);
                        sensorEntry.put("data", data);
                        sensorDataMap.put(key, sensorEntry);

                        // count the added point to the total number of sensor data
                        points++;

                    } else {
                        // if the data type is a "file", we need special handling
                        Log.d(TAG,
                                "Transmit file separately from the other buffered data points: '"
                                        + value + "'");

                        // create sensor data JSON object with only 1 data point
                        JSONObject sensorData = new JSONObject();
                        JSONArray dataArray = new JSONArray();
                        JSONObject data = new JSONObject();
                        data.put("value", value);
                        data.put("date", dateFormatter.format(timestamp / 1000d));
                        dataArray.put(data);
                        sensorData.put("data", dataArray);

                        sendSensorData(sensorName, sensorData, dataType, sensorDesc);
                    }

                    cursor.moveToNext();
                }

                if (sensorDataMap.size() < 1) {
                    // no data to transmit
                    continue;
                }

                // prepare the main JSON object for transmission
                JSONArray sensors = new JSONArray();
                for (Entry<String, JSONObject> entry : sensorDataMap.entrySet()) {
                    sensors.put(entry.getValue());
                }
                JSONObject transmission = new JSONObject();
                transmission.put("sensors", sensors);

                // perform the actual POST request
                postData(transmission);
            }
        }

        /**
         * POSTs the sensor data points to the main sensor data URL at CommonSense.
         * 
         * @param transmission
         *            JSON Object with data points for transmission
         * @throws JSONException
         * @throws MalformedURLException
         */
        private void postData(JSONObject transmission) throws JSONException, MalformedURLException {

            HashMap<String, String> response = SenseApi.sendJson(context, url, transmission,
                    "POST", cookie);

            if (response == null) {
                // Error when sending
                Log.w(TAG, "Failed to send buffered data points.\nData will be retried later.");

            } else if (response.get("http response code").compareToIgnoreCase("201") != 0) {
                // incorrect status code
                String statusCode = response.get("http response code");

                // if un-authorized: relogin
                if (statusCode.compareToIgnoreCase("403") == 0) {
                    final Intent serviceIntent = new Intent(ISenseService.class.getName());
                    serviceIntent.putExtra(SenseService.ACTION_RELOGIN, true);
                    context.startService(serviceIntent);
                }

                // Show the HTTP response Code
                Log.w(TAG, "Failed to send buffered data points: " + statusCode
                        + ", Response content: '" + response.get("content") + "'\n"
                        + "Data will be retried later");

            } else {
                // Data sent successfully
                onTransmitSuccess(transmission);
            }
        }

        /**
         * @param bytes
         *            Byte count;
         * @param si
         *            true to use SI system, where 1000 B = 1 kB
         * @return A String with human-readable byte count, including suffix.
         */
        public String humanReadableByteCount(long bytes, boolean si) {
            int unit = si ? 1000 : 1024;
            if (bytes < unit)
                return bytes + " B";
            int exp = (int) (Math.log(bytes) / Math.log(unit));
            String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
            return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
        }
    }

    /**
     * Handler for transmit tasks of persisted data (i.e. data that was stored in the SQLite
     * database). Removes the data from the database after the transmission is completed
     * successfully.
     */
    private class PersistedDataTransmitHandler extends AbstractDataTransmitHandler {

        public PersistedDataTransmitHandler(Context context, Looper looper) {
            super(context, looper);
        }

        @Override
        protected Cursor getUnsentData() {
            String where = DataPoint.TRANSMIT_STATE + "=0";
            Cursor unsent = getContentResolver().query(DataPoint.CONTENT_PERSISTED_URI, null,
                    where, null, null);
            if (null != unsent && unsent.moveToFirst()) {
                Log.v(TAG, "Found " + unsent.getCount()
                        + " unsent data points in persistant storage");
                return unsent;
            } else {
                Log.v(TAG, "No unsent data points in the persistant storage");
                return new MatrixCursor(new String[]{});
            }
        }

        @Override
        protected void onTransmitSuccess(JSONObject transmission) throws JSONException {

            // log our great success
            int bytes = transmission.toString().getBytes().length;
            Log.i(TAG, "Sent old sensor data from persistant storage! Raw data size: "
                    + humanReadableByteCount(bytes, false));

            JSONArray sensors = SenseApi.getRegisteredSensors(context);
            if (sensors == null) {
                Log.e(TAG, "List of registered sensors is unavailable right after data transfer?!");
                return;
            }

            JSONArray sensorDatas = transmission.getJSONArray("sensors");
            for (int i = 0; i < sensorDatas.length(); i++) {

                JSONObject sensorData = sensorDatas.getJSONObject(i);

                // get the name of the sensor, to use in the ContentResolver query
                String sensorId = sensorData.getString("sensor_id");
                String sensorName = null;
                for (int j = 0; j < sensors.length(); j++) {
                    if (sensorId.equals(sensors.getJSONObject(j).get("id"))) {
                        sensorName = sensors.getJSONObject(j).getString("name");
                        break;
                    }
                }

                // select points for this sensor, between the first and the last time stamp
                JSONArray dataPoints = sensorData.getJSONArray("data");
                String frstTimeStamp = dataPoints.getJSONObject(0).getString("date");
                String lastTimeStamp = dataPoints.getJSONObject(dataPoints.length() - 1).getString(
                        "date");
                long min = Math.round(Double.parseDouble(frstTimeStamp) * 1000);
                long max = Math.round(Double.parseDouble(lastTimeStamp) * 1000);
                String where = DataPoint.SENSOR_NAME + "='" + sensorName + "'" + " AND "
                        + DataPoint.TIMESTAMP + ">=" + min + " AND " + DataPoint.TIMESTAMP + " <="
                        + max;

                // delete the data from the storage
                int deleted = getContentResolver().delete(DataPoint.CONTENT_PERSISTED_URI, where,
                        null);
                if (deleted == dataPoints.length()) {
                    Log.v(TAG, "Deleted all " + deleted + " '" + sensorName
                            + "' points from the persistant storage");
                } else {
                    Log.w(TAG, "Wrong number of '" + sensorName
                            + "' data points deleted after transmission! " + deleted + " vs. "
                            + dataPoints.length());
                }
            }
        }
    }

    /**
     * Handler for transmit tasks of recently added data (i.e. data that is stored in system RAM
     * memory). Updates {@link DataPoint#TRANSMIT_STATE} of the data points after the transmission
     * is completed successfully.
     */
    private class RecentDataTransmitHandler extends AbstractDataTransmitHandler {

        public RecentDataTransmitHandler(Context context, Looper looper) {
            super(context, looper);
        }

        @Override
        protected Cursor getUnsentData() {
            String where = DataPoint.TRANSMIT_STATE + "=0";
            Cursor unsent = getContentResolver().query(DataPoint.CONTENT_URI, null, where, null,
                    null);
            if (null != unsent && unsent.moveToFirst()) {
                Log.v(TAG, "Found " + unsent.getCount() + " unsent data points in local storage");
                return unsent;
            } else {
                Log.v(TAG, "No unsent recent data points");
                return new MatrixCursor(new String[]{});
            }
        }

        @Override
        protected void onTransmitSuccess(JSONObject transmission) throws JSONException {

            // log our great success
            int bytes = transmission.toString().getBytes().length;
            Log.i(TAG, "Sent recent sensor data from the local storage! Raw data size: "
                    + humanReadableByteCount(bytes, false));

            // new content values with updated transmit state
            ContentValues values = new ContentValues();
            values.put(DataPoint.TRANSMIT_STATE, 1);

            JSONArray sensors = SenseApi.getRegisteredSensors(context);
            if (sensors == null) {
                Log.e(TAG, "List of registered sensors is unavailable right after data transfer?!");
                return;
            }

            JSONArray sensorDatas = transmission.getJSONArray("sensors");
            for (int i = 0; i < sensorDatas.length(); i++) {

                JSONObject sensorData = sensorDatas.getJSONObject(i);

                // get the name of the sensor, to use in the ContentResolver query
                String sensorId = sensorData.getString("sensor_id");
                String sensorName = null;
                for (int j = 0; j < sensors.length(); j++) {
                    if (sensorId.equals(sensors.getJSONObject(j).get("id"))) {
                        sensorName = sensors.getJSONObject(j).getString("name");
                        break;
                    }
                }

                // select points for this sensor, between the first and the last time stamp
                JSONArray dataPoints = sensorData.getJSONArray("data");
                String frstTimeStamp = dataPoints.getJSONObject(0).getString("date");
                String lastTimeStamp = dataPoints.getJSONObject(dataPoints.length() - 1).getString(
                        "date");
                long min = Math.round(Double.parseDouble(frstTimeStamp) * 1000);
                long max = Math.round(Double.parseDouble(lastTimeStamp) * 1000);
                String where = DataPoint.SENSOR_NAME + "='" + sensorName + "'" + " AND "
                        + DataPoint.TIMESTAMP + ">=" + min + " AND " + DataPoint.TIMESTAMP + " <="
                        + max;

                int updated = getContentResolver().update(DataPoint.CONTENT_URI, values, where,
                        null);
                if (updated == dataPoints.length()) {
                    Log.v(TAG, "Updated all " + updated + " '" + sensorName
                            + "' data points in the local storage");
                } else {
                    Log.w(TAG, "Wrong number of '" + sensorName
                            + "' data points updated after transmission! " + updated + " vs. "
                            + dataPoints.length());
                }
            }
        }
    }

    private class SendDataThread extends Handler {

        private final String cookie;
        private final String sensorName;
        private final String deviceType;
        private final String dataType;
        private final Context context;
        private final JSONObject data;
        private WakeLock wakeLock;

        public SendDataThread(String cookie, JSONObject data, String sensorName, String dataType,
                String deviceType, Context context, Looper looper) {
            super(looper);
            this.cookie = cookie;
            this.data = data;
            this.sensorName = sensorName;
            this.dataType = dataType;
            this.deviceType = deviceType != null ? deviceType : sensorName;
            this.context = context;
        }

        private String getSensorUrl() {
            String url = null;
            try {
                String sensorValue = (String) ((JSONObject) ((JSONArray) data.get("data")).get(0))
                        .get("value");
                url = MsgHandler.this.getSensorUrl(context, sensorName, sensorValue, dataType,
                        deviceType);
            } catch (Exception e) {
                Log.e(TAG, "Exception retrieving sensor URL from API", e);
            }
            return url;
        }

        @Override
        public void handleMessage(Message msg) {

            try {
                // make sure the device stays awake while transmitting
                wakeLock = getWakeLock();
                wakeLock.acquire();

                // get sensor URL at CommonSense
                String url = getSensorUrl();

                if (url == null) {
                    Log.w(TAG, "Received invalid sensor URL for '" + sensorName
                            + "': requeue the message.");
                    bufferMessage(sensorName, data, dataType, deviceType);
                    return;
                }

                HashMap<String, String> response = SenseApi.sendJson(context, new URL(url), data,
                        "POST", cookie);
                // Error when sending
                if (response == null
                        || response.get("http response code").compareToIgnoreCase("201") != 0) {

                    // if un-authorized: relogin
                    if (response != null
                            && response.get("http response code").compareToIgnoreCase("403") == 0) {
                        final Intent serviceIntent = new Intent(ISenseService.class.getName());
                        serviceIntent.putExtra(SenseService.ACTION_RELOGIN, true);
                        context.startService(serviceIntent);
                    }

                    // Show the HTTP response Code
                    if (response != null) {
                        Log.w(TAG, "Failed to send '" + sensorName + "' data. Response code:"
                                + response.get("http response code") + ", Response content: '"
                                + response.get("content") + "'\nMessage will be requeued");
                    } else {
                        Log.w(TAG, "Failed to send '" + sensorName
                                + "' data.\nMessage will be requeued.");
                    }

                    // connection error put all the messages back in the queue
                    bufferMessage(sensorName, data, dataType, deviceType);
                }

                // Data sent successfully
                else {
                    int bytes = data.toString().getBytes().length;
                    Log.i(TAG, "Sent '" + sensorName + "' data! Raw data size: " + bytes + " bytes");

                    updateTransmitState();
                }

            } catch (Exception e) {
                if (null != e.getMessage()) {
                    Log.e(TAG, "Exception sending '" + sensorName
                            + "' data, message will be requeued: " + e.getMessage());
                } else {
                    Log.e(TAG, "Exception sending '" + sensorName
                            + "' data, message will be requeued.", e);
                }
                bufferMessage(sensorName, data, dataType, deviceType);

            } finally {
                stopAndCleanup();
            }
        }

        private void stopAndCleanup() {
            --nrOfSendMessageThreads;
            wakeLock.release();
            getLooper().quit();
        }

        private void updateTransmitState() throws JSONException {
            // new content values with updated transmit state
            ContentValues values = new ContentValues();
            values.put(DataPoint.TRANSMIT_STATE, 1);

            // select points for this sensor, between the fist and the last time stamp
            JSONArray dataPoints = data.getJSONArray("data");
            String frstTimeStamp = dataPoints.getJSONObject(0).getString("date");
            String lastTimeStamp = dataPoints.getJSONObject(dataPoints.length() - 1).getString(
                    "date");
            long min = Math.round(Double.parseDouble(frstTimeStamp) * 1000);
            long max = Math.round(Double.parseDouble(lastTimeStamp) * 1000);
            String where = DataPoint.SENSOR_NAME + "='" + sensorName + "'" + " AND "
                    + DataPoint.TIMESTAMP + ">=" + min + " AND " + DataPoint.TIMESTAMP + " <="
                    + max;

            int updated = getContentResolver().update(DataPoint.CONTENT_URI, values, where, null);
            if (updated == dataPoints.length()) {
                // Log.v(TAG, "Updated all " + updated + " rows in the local storage");
            } else {
                Log.w(TAG, "Wrong number of local storage points updated! " + updated + " vs. "
                        + dataPoints.length());
            }
        }
    }

    private class SendFileThread extends Handler {

        private final String cookie;
        private final JSONObject data;
        private final String sensorName;
        private final String dataType;
        private final String deviceType;
        private final Context context;
        private WakeLock wakeLock;

        public SendFileThread(String cookie, JSONObject data, String sensorName, String dataType,
                String deviceType, Context context, Looper looper) {
            super(looper);
            this.cookie = cookie;
            this.data = data;
            this.sensorName = sensorName;
            this.dataType = dataType;
            this.deviceType = deviceType;
            this.context = context;
        }

        private String getSensorUrl() {
            String url = null;
            try {
                final String f_deviceType = deviceType != null ? deviceType : sensorName;
                String dataStructure = (String) ((JSONObject) ((JSONArray) data.get("data")).get(0))
                        .get("value");
                url = SenseApi.getSensorUrl(context, sensorName, dataStructure, dataType,
                        f_deviceType);
            } catch (Exception e) {
                Log.e(TAG, "Exception retrieving sensor URL from API", e);
            }
            return url;
        }

        @Override
        public void handleMessage(Message message) {

            try {
                // make sure the device stays awake while transmitting
                wakeLock = getWakeLock();
                wakeLock.acquire();

                // get sensor URL from CommonSense
                String urlStr = getSensorUrl();

                if (urlStr == null) {
                    Log.w(TAG, "Received invalid sensor URL for '" + sensorName + "'. Data lost.");
                    return;
                }

                // submit each file separately
                JSONArray data = (JSONArray) this.data.get("data");
                for (int i = 0; i < data.length(); i++) {
                    JSONObject object = (JSONObject) data.get(i);
                    String fileName = (String) object.get("value");

                    HttpURLConnection conn = null;

                    DataOutputStream dos = null;

                    // OutputStream os = null;
                    // boolean ret = false;

                    String lineEnd = "\r\n";
                    String twoHyphens = "--";
                    String boundary = "----FormBoundary6bYQOdhfGEj4oCSv";

                    int bytesRead, bytesAvailable, bufferSize;

                    byte[] buffer;

                    int maxBufferSize = 1 * 1024 * 1024;

                    // ------------------ CLIENT REQUEST

                    FileInputStream fileInputStream = new FileInputStream(new File(fileName));

                    // open a URL connection to the Servlet

                    URL url = new URL(urlStr);

                    // Open a HTTP connection to the URL

                    conn = (HttpURLConnection) url.openConnection();

                    // Allow Inputs
                    conn.setDoInput(true);

                    // Allow Outputs
                    conn.setDoOutput(true);

                    // Don't use a cached copy.
                    conn.setUseCaches(false);

                    // Use a post method.
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Cookie", cookie);
                    conn.setRequestProperty("Connection", "Keep-Alive");

                    conn.setRequestProperty("Content-Type", "multipart/form-data;boundary="
                            + boundary);

                    dos = new DataOutputStream(conn.getOutputStream());

                    dos.writeBytes(twoHyphens + boundary + lineEnd);
                    dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""
                            + fileName + "\"" + lineEnd);
                    dos.writeBytes(lineEnd);
                    // create a buffer of maximum size
                    bytesAvailable = fileInputStream.available();
                    bufferSize = Math.min(bytesAvailable, maxBufferSize);
                    buffer = new byte[bufferSize];

                    // read file and write it into form...

                    bytesRead = fileInputStream.read(buffer, 0, bufferSize);

                    while (bytesRead > 0) {
                        dos.write(buffer, 0, bufferSize);
                        bytesAvailable = fileInputStream.available();
                        bufferSize = Math.min(bytesAvailable, maxBufferSize);
                        bytesRead = fileInputStream.read(buffer, 0, bufferSize);
                    }

                    // send multipart form data necesssary after file data...

                    dos.writeBytes(lineEnd);
                    dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);

                    // close streams

                    fileInputStream.close();
                    dos.flush();
                    dos.close();

                    if (conn.getResponseCode() != 201) {
                        Log.e(TAG,
                                "Sending '" + sensorName
                                        + "' sensor file failed. Data lost. Response code:"
                                        + conn.getResponseCode());
                    } else {
                        Log.i(TAG, "Sent '" + sensorName + "' sensor value file OK!");
                        String date = (String) object.get("date");
                        updateTransmitState(date);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Sending '" + sensorName + "' sensor file failed. Data lost.", e);
            } finally {
                stopAndCleanup();
            }
        }

        private void stopAndCleanup() {
            --nrOfSendMessageThreads;
            wakeLock.release();
            getLooper().quit();
        }

        private void updateTransmitState(String date) {

            // new content values with updated transmit state
            ContentValues values = new ContentValues();
            values.put(DataPoint.TRANSMIT_STATE, 1);

            long timestamp = Math.round(Double.parseDouble(date) * 1000);
            String where = DataPoint.SENSOR_NAME + "='" + sensorName + "'" + " AND "
                    + DataPoint.TIMESTAMP + "=" + timestamp;

            int updated = getContentResolver().update(DataPoint.CONTENT_URI, values, where, null);
            int deleted = 0;
            if (0 == updated) {
                deleted = getContentResolver().delete(DataPoint.CONTENT_PERSISTED_URI, where, null);
            }
            if (deleted == 1 || updated == 1) {
                // ok
            } else {
                Log.w(TAG,
                        "Failed to update the local storage after a file was successfully sent to CommonSense!");
            }

        }
    }

    private static final String TAG = "Sense MsgHandler";
    public static final String ACTION_NEW_MSG = "nl.sense_os.app.MsgHandler.NEW_MSG";
    public static final String ACTION_NEW_FILE = "nl.sense_os.app.MsgHandler.NEW_FILE";
    public static final String ACTION_SEND_DATA = "nl.sense_os.app.MsgHandler.SEND_DATA";
    public static final String KEY_DATA_TYPE = "data_type";
    public static final String KEY_SENSOR_DEVICE = "sensor_device";
    public static final String KEY_SENSOR_NAME = "sensor_name";
    public static final String KEY_TIMESTAMP = "timestamp";
    public static final String KEY_VALUE = "value";
    private static final int MAX_NR_OF_SEND_MSG_THREADS = 50;
    private static final int MAX_POST_DATA = 100;
    private static final int MAX_POST_DATA_TIME_SERIE = 10;
    private JSONObject buffer;
    private int bufferCount;
    private int nrOfSendMessageThreads = 0;
    private WakeLock wakeLock;

    private RecentDataTransmitHandler recentDataTransmitter;
    private PersistedDataTransmitHandler persistedDataTransmitter;

    /**
     * Buffers a data point in the memory, for scheduled transmission later on.
     * 
     * @param sensorName
     *            Sensor name.
     * @param sensorValue
     *            Sensor value, stored as a String.
     * @param timeInSecs
     *            Timestamp of the data point, in seconds and properly formatted.
     * @param dataType
     *            Data type of the point.
     * @param deviceType
     *            Sensor device type.
     */
    private void bufferDataPoint(String sensorName, String sensorValue, String timeInSecs,
            String dataType, String deviceType) {
        // Log.d(TAG, "Buffer '" + sensorName + "' data for transmission later on");

        // try {
        // // create JSON object for buffering
        // JSONObject json = new JSONObject();
        // json.put("name", sensorName);
        // json.put("time", timeInSecs);
        // json.put("type", dataType);
        // json.put("device", deviceType);
        // json.put("val", sensorValue);
        //
        // int jsonBytes = json.toString().length();
        //
        // // check if there is room in the buffer
        // if (bufferCount + jsonBytes >= MAX_BUFFER) {
        // // empty buffer into database
        // // Log.v(TAG, "Buffer overflow! Emptying buffer to database");
        // emptyBufferToDb();
        // }
        //
        // // put data in buffer
        // String sensorKey = sensorName + "_" + deviceType;
        // JSONArray dataArray = buffer.optJSONArray(sensorKey);
        // if (dataArray == null) {
        // dataArray = new JSONArray();
        // }
        // dataArray.put(json);
        // buffer.put(sensorKey, dataArray);
        // bufferCount += jsonBytes;
        //
        // } catch (Exception e) {
        // Log.e(TAG, "Error in buffering data:" + e.getMessage());
        // }
    }

    /**
     * Puts a failed sensor data message back in the buffer, for transmission later on.
     * 
     * @param sensorName
     *            Sensor name.
     * @param messageData
     *            JSON object with array of sensor data points that have to be buffered again.
     * @param dataType
     *            Sensor data type.
     * @param deviceType
     *            Sensor device type.
     */
    private void bufferMessage(String sensorName, JSONObject messageData, String dataType,
            String deviceType) {
        // Log.v(TAG, "Buffer sensor data from failed transmission...");

        // try {
        // JSONArray dataArray = messageData.getJSONArray("data");
        //
        // // put each data point in the buffer individually
        // for (int index = 0; index < dataArray.length(); index++) {
        // JSONObject mysteryJson = dataArray.getJSONObject(index);
        // String value = mysteryJson.getString("value");
        // String date = mysteryJson.getString("date");
        // bufferDataPoint(sensorName, value, date, dataType, deviceType);
        // // Log.v(TAG, sensorName + " data buffered.");
        // }
        //
        // } catch (Exception e) {
        // Log.e(TAG, "Error in buffering failed message:", e);
        // }
    }

    /**
     * Puts data from the buffer in the flash database for long-term storage
     */
    private void emptyBufferToDb() {
        Log.v(TAG, "Emptying buffer to persistant database...");

        String where = DataPoint.TRANSMIT_STATE + "=" + 0;
        getContentResolver().update(Uri.parse(DataPoint.CONTENT_URI.toString() + "?persist=true"),
                new ContentValues(), where, null);

        // try {
        // openDb();
        // JSONArray names = buffer.names();
        // if (names == null) {
        // return;
        // }
        // for (int i = 0; i < names.length(); i++) {
        // JSONArray sensorArray = buffer.getJSONArray(names.getString(i));
        //
        // for (int x = 0; x < sensorArray.length(); x++) {
        // ContentValues values = new ContentValues();
        // values.put(BufferedData.JSON, ((JSONObject) sensorArray.get(x)).toString());
        // values.put(BufferedData.SENSOR, names.getString(i));
        // values.put(BufferedData.ACTIVE, false);
        // db.insert(DbHelper.TABLE_NAME, null, values);
        // }
        // }
        // // reset buffer
        // bufferCount = 0;
        // buffer = new JSONObject();
        // } catch (Exception e) {
        // Log.e(TAG, "Error storing buffer in persistant database!", e);
        // } finally {
        // closeDb();
        // }
    }

    /**
     * Calls through to the Sense API class. This method is synchronized to make sure that multiple
     * thread do not create multiple sensors at the same time.
     * 
     * @return The URL of the sensor at CommonSense, or null if an error occurred.
     */
    private synchronized String getSensorUrl(Context context, String sensorName,
            String sensorValue, String dataType, String deviceType) {
        String url = SenseApi.getSensorUrl(context, sensorName, sensorValue, dataType, deviceType);
        return url;
    }

    private WakeLock getWakeLock() {
        if (null == wakeLock) {
            PowerManager powerMgr = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = powerMgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        }
        return wakeLock;
    }

    /**
     * Handles an incoming Intent that started the service by checking if it wants to store a new
     * message or if it wants to send data to CommonSense.
     */
    private void handleIntent(Intent intent, int flags, int startId) {

        final String action = intent.getAction();

        if (action != null && action.equals(ACTION_NEW_MSG)) {
            handleNewMsgIntent(intent);
        } else if (action != null && action.equals(ACTION_SEND_DATA)) {
            handleSendIntent(intent);
        } else {
            Log.e(TAG, "Unexpected intent action: " + action);
        }
    }

    private void handleNewMsgIntent(Intent intent) {
        // Log.d(TAG, "handleNewMsgIntent");

        try {
            DecimalFormatSymbols otherSymbols = new DecimalFormatSymbols(Locale.ENGLISH);
            NumberFormat formatter = new DecimalFormat("##########.###", otherSymbols);

            // get data point details from Intent
            String sensorName = intent.getStringExtra(KEY_SENSOR_NAME);
            String dataType = intent.getStringExtra(KEY_DATA_TYPE);
            String timeInSecs = formatter.format(intent.getLongExtra(KEY_TIMESTAMP,
                    System.currentTimeMillis()) / 1000.0d);
            String deviceType = intent.getStringExtra(KEY_SENSOR_DEVICE);
            deviceType = deviceType != null ? deviceType : sensorName;

            // convert sensor value to String
            String sensorValue = "";
            if (dataType.equals(SenseDataTypes.BOOL)) {
                sensorValue += intent.getBooleanExtra(KEY_VALUE, false);
            } else if (dataType.equals(SenseDataTypes.FLOAT)) {
                sensorValue += intent.getFloatExtra(KEY_VALUE, Float.MIN_VALUE);
            } else if (dataType.equals(SenseDataTypes.INT)) {
                sensorValue += intent.getIntExtra(KEY_VALUE, Integer.MIN_VALUE);
            } else if (dataType.equals(SenseDataTypes.JSON)
                    || dataType.equals(SenseDataTypes.JSON_TIME_SERIE)) {
                sensorValue += new JSONObject(intent.getStringExtra(KEY_VALUE)).toString();
            } else if (dataType.equals(SenseDataTypes.STRING)
                    || dataType.equals(SenseDataTypes.FILE)) {
                sensorValue += intent.getStringExtra(KEY_VALUE);
            }

            // check if we can send the data point immediately
            SharedPreferences mainPrefs = getSharedPreferences(SensePrefs.MAIN_PREFS, MODE_PRIVATE);
            int rate = Integer.parseInt(mainPrefs.getString(Main.SYNC_RATE, "0"));
            boolean isMaxThreads = nrOfSendMessageThreads >= MAX_NR_OF_SEND_MSG_THREADS - 5;
            boolean isRealTimeMode = rate == -2;
            if (isOnline() && isRealTimeMode && !isMaxThreads) {
                /* send immediately */

                // create sensor data JSON object with only 1 data point
                JSONObject sensorData = new JSONObject();
                JSONArray dataArray = new JSONArray();
                JSONObject data = new JSONObject();
                data.put("value", sensorValue);
                data.put("date", timeInSecs);
                dataArray.put(data);
                sensorData.put("data", dataArray);

                sendSensorData(sensorName, sensorData, dataType, deviceType);

            } else {
                /* buffer data if there is no connectivity */
                bufferDataPoint(sensorName, sensorValue, timeInSecs, dataType, deviceType);
            }

            // put the data point in the local storage
            // if (mainPrefs.getBoolean(Advanced.LOCAL_STORAGE, true)) {
            insertToLocalStorage(sensorName, deviceType, dataType,
                    intent.getLongExtra(KEY_TIMESTAMP, System.currentTimeMillis()), sensorValue);
            // }

        } catch (Exception e) {
            Log.e(TAG, "Failed to handle new data point!", e);
        }
    }

    private void handleSendIntent(Intent intent) {
        if (isOnline()) {
            // get the cookie
            final SharedPreferences prefs = getSharedPreferences(SensePrefs.AUTH_PREFS,
                    Context.MODE_PRIVATE);
            String cookie = prefs.getString(Auth.LOGIN_COOKIE, "");

            // prepare the data to give to the transmitters
            Bundle msgData = new Bundle();
            msgData.putString("cookie", cookie);

            Message msg = Message.obtain();
            msg.setData(msgData);
            persistedDataTransmitter.sendMessage(msg);

            msg = Message.obtain();
            msg.setData(msgData);
            recentDataTransmitter.sendMessage(msg);
        }
    }

    /**
     * Inserts a data point as new row in the local storage. Removal of old points is done
     * automatically.
     * 
     * @param sensorName
     * @param sensorDescription
     * @param dataType
     * @param timestamp
     * @param value
     */
    private void insertToLocalStorage(String sensorName, String sensorDescription, String dataType,
            long timestamp, String value) {

        // new value
        ContentValues values = new ContentValues();
        values.put(DataPoint.SENSOR_NAME, sensorName);
        values.put(DataPoint.SENSOR_DESCRIPTION, sensorDescription);
        values.put(DataPoint.DATA_TYPE, dataType);
        values.put(DataPoint.TIMESTAMP, timestamp);
        values.put(DataPoint.VALUE, value);
        values.put(DataPoint.TRANSMIT_STATE, 0);

        getContentResolver().insert(DataPoint.CONTENT_URI, values);
    }

    /**
     * @return <code>true</code> if the phone has network connectivity.
     */
    private boolean isOnline() {
        SharedPreferences mainPrefs = getSharedPreferences(SensePrefs.MAIN_PREFS, MODE_PRIVATE);
        boolean isCommonSenseEnabled = mainPrefs.getBoolean(Main.Advanced.USE_COMMONSENSE, true);

        SharedPreferences authPrefs = getSharedPreferences(SensePrefs.AUTH_PREFS, MODE_PRIVATE);
        boolean isLoggedIn = authPrefs.getString(Auth.LOGIN_COOKIE, null) != null;

        final ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        final NetworkInfo info = cm.getActiveNetworkInfo();
        return null != info && info.isConnected() && isCommonSenseEnabled && isLoggedIn;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // you cannot bind to this service
        return null;
    }

    @Override
    public void onCreate() {
        // Log.v(TAG, "onCreate");
        super.onCreate();

        HandlerThread recentDataThread = new HandlerThread("TransmitRecentDataThread");
        recentDataThread.start();
        recentDataTransmitter = new RecentDataTransmitHandler(this, recentDataThread.getLooper());

        HandlerThread persistedDataThread = new HandlerThread("TransmitPersistedDataThread");
        persistedDataThread.start();
        persistedDataTransmitter = new PersistedDataTransmitHandler(this,
                persistedDataThread.getLooper());

    }

    @Override
    public void onDestroy() {
        // Log.v(TAG, "onDestroy");
        emptyBufferToDb();

        // stop buffered data transmission threads
        persistedDataTransmitter.getLooper().quit();
        recentDataTransmitter.getLooper().quit();

        super.onDestroy();
    }

    /**
     * Deprecated method for starting the service, used in 1.6 and older.
     */
    @Override
    public void onStart(Intent intent, int startid) {
        handleIntent(intent, 0, startid);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handleIntent(intent, flags, startId);

        // this service is not sticky, it will get an intent to restart it if necessary
        return START_NOT_STICKY;
    }

    /**
     * Puts a message with sensor data in the queue for the MsgHandler again, for immediate
     * retrying.
     * 
     * @param sensorName
     *            Name of the sensor.
     * @param data
     *            JSON sensor data, with multiple data points.
     * @param dataType
     *            Sensor data type.
     * @param deviceType
     *            Sensor device type.
     * @param context
     *            Application context, used to call the MsgHandler.
     */
    private void requeueMessage(String sensorName, JSONObject data, String dataType,
            String deviceType, Context context) {
        // try {
        // JSONArray dataArray = data.getJSONArray("data");
        // for (int index = 0; index < dataArray.length(); index++) {
        // Intent i = new Intent(MsgHandler.ACTION_NEW_MSG);
        // i.putExtra(MsgHandler.KEY_SENSOR_NAME, sensorName);
        // i.putExtra(MsgHandler.KEY_SENSOR_DEVICE, deviceType);
        // i.putExtra(MsgHandler.KEY_DATA_TYPE, dataType);
        // i.putExtra(MsgHandler.KEY_TIMESTAMP, (long) ((float) Double.parseDouble(dataArray
        // .getJSONObject(index).getString("date")) * 1000f));
        // String value = dataArray.getJSONObject(index).getString("value");
        // if (dataType.equals(SenseDataTypes.BOOL)) {
        // i.putExtra(MsgHandler.KEY_VALUE, Boolean.getBoolean(value));
        // } else if (dataType.equals(SenseDataTypes.FLOAT)) {
        // i.putExtra(MsgHandler.KEY_VALUE, Float.parseFloat(value));
        // } else if (dataType.equals(SenseDataTypes.INT)) {
        // i.putExtra(MsgHandler.KEY_VALUE, Integer.parseInt(value));
        // } else {
        // i.putExtra(MsgHandler.KEY_VALUE, value);
        // }
        // context.startService(i);
        // // Log.v(TAG, sensorName + " data requeued.");
        // }
        //
        // } catch (Exception e) {
        // Log.e(TAG, "Error in sending sensor data:", e);
        // }
    }

    private boolean sendDataFromBuffer() {

        if (bufferCount > 0) {
            // Log.v(TAG, "Sending " + bufferCount + " bytes from local buffer to CommonSense");
            try {
                int sentCount = 0;
                int sentIndex = 0;
                JSONArray names = buffer.names();
                int max_post_sensor_data = MAX_POST_DATA;
                for (int i = 0; i < names.length(); i++) {
                    JSONArray sensorArray = buffer.getJSONArray(names.getString(i));
                    JSONObject sensorData = new JSONObject();
                    JSONArray dataArray = new JSONArray();
                    if (((JSONObject) sensorArray.get(0)).getString("type").equalsIgnoreCase(
                            SenseDataTypes.JSON_TIME_SERIE)) {
                        max_post_sensor_data = MAX_POST_DATA_TIME_SERIE;
                    }
                    for (int x = sentIndex; x < sensorArray.length()
                            && sentCount - sentIndex < max_post_sensor_data; x++) {
                        JSONObject data = new JSONObject();
                        JSONObject sensor = (JSONObject) sensorArray.get(x);
                        data.put("value", sensor.getString("val"));
                        data.put("date", sensor.get("time"));
                        dataArray.put(data);
                        ++sentCount;
                    }

                    sensorData.put("data", dataArray);
                    JSONObject sensor = (JSONObject) sensorArray.get(0);
                    sendSensorData(sensor.getString("name"), sensorData, sensor.getString("type"),
                            sensor.getString("device"));

                    // if MAX_POST_DATA reached but their are still some items left, then do the
                    // rest --i;
                    if (sentCount != sensorArray.length()) {
                        --i;
                        sentIndex = sentCount;
                    } else {
                        sentIndex = sentCount = 0;
                    }
                }
                // Log.d(TAG, "Buffered sensor values sent OK");
                buffer = new JSONObject();
                bufferCount = 0;
            } catch (Exception e) {
                Log.e(TAG, "Error sending data from buffer:" + e.getMessage());
            }

        } else {
            // TODO smart transmission scaling
        }

        return true;
    }

    private boolean sendDataFromDb() {
        // Cursor c = null;
        // boolean emptyDataBase = false;
        // String limit = "90";
        //
        // try {
        // // query the database
        // openDb();
        // String[] cols = { BufferedData._ID, BufferedData.JSON, BufferedData.SENSOR };
        // String sel = BufferedData.ACTIVE + "!=\'true\'";
        // while (!emptyDataBase) {
        // c = db.query(DbHelper.TABLE_NAME, cols, sel, null, null, null, BufferedData.SENSOR,
        // limit);
        //
        // if (c.getCount() > 0) {
        // // Log.v(TAG, "Sending " + c.getCount() + " values from DB to CommonSense");
        //
        // // Send Data from each sensor
        // int sentCount = 0;
        // String sensorKey = "";
        // JSONObject sensorData = new JSONObject();
        // JSONArray dataArray = new JSONArray();
        // String sensorName = "";
        // String sensorType = "";
        // String sensorDevice = "";
        // c.moveToFirst();
        // int max_post_sensor_data = MAX_POST_DATA;
        // while (false == c.isAfterLast()) {
        // if (sensorType.equalsIgnoreCase(SenseDataTypes.JSON_TIME_SERIE)) {
        // max_post_sensor_data = MAX_POST_DATA_TIME_SERIE;
        // }
        // if (c.getString(2).compareToIgnoreCase(sensorKey) != 0
        // || sentCount >= max_post_sensor_data) {
        // // send the in the previous rounds collected data
        // if (sensorKey.length() > 0) {
        // sensorData.put("data", dataArray);
        // sendSensorData(sensorName, sensorData, sensorType, sensorDevice);
        // sensorData = new JSONObject();
        // dataArray = new JSONArray();
        // }
        // }
        //
        // JSONObject sensor = new JSONObject(c.getString(1));
        // JSONObject data = new JSONObject();
        // data.put("value", sensor.getString("val"));
        // data.put("date", sensor.get("time"));
        // if (dataArray.length() == 0) {
        // sensorName = sensor.getString("name");
        // sensorType = sensor.getString("type");
        // sensorDevice = sensor.getString("device");
        // }
        // dataArray.put(data);
        // sensorKey = c.getString(2);
        // // if last, then send
        // if (c.isLast()) {
        // sensorData.put("data", dataArray);
        // sendSensorData(sensorName, sensorData, sensorType, sensorDevice);
        // }
        // sentCount++;
        // c.moveToNext();
        // }
        //
        // // Log.d(TAG, "Sensor values from database sent OK!");
        //
        // // remove data from database
        // c.moveToFirst();
        // while (false == c.isAfterLast()) {
        // int id = c.getInt(c.getColumnIndex(BufferedData._ID));
        // String where = BufferedData._ID + "=?";
        // String[] whereArgs = { "" + id };
        // db.delete(DbHelper.TABLE_NAME, where, whereArgs);
        // c.moveToNext();
        // }
        // c.close();
        // c = null;
        // } else {
        // emptyDataBase = true;
        // // TODO smart transmission scaling
        // }
        // }
        // } catch (Exception e) {
        // Log.e(TAG, "Error in sending data from database!", e);
        // return false;
        // } finally {
        // if (c != null) {
        // c.close();
        // }
        // closeDb();
        // }
        return true;
    }

    private void sendSensorData(final String sensorName, final JSONObject sensorData,
            final String dataType, final String deviceType) {

        try {
            if (nrOfSendMessageThreads >= MAX_NR_OF_SEND_MSG_THREADS) {
                requeueMessage(sensorName, sensorData, dataType, deviceType, this);

            } else {
                final SharedPreferences prefs = getSharedPreferences(SensePrefs.AUTH_PREFS,
                        Context.MODE_PRIVATE);
                String cookie = prefs.getString(Auth.LOGIN_COOKIE, "");

                // check for sending a file
                if (dataType.equals(SenseDataTypes.FILE)) {
                    if (nrOfSendMessageThreads < MAX_NR_OF_SEND_MSG_THREADS) {
                        ++nrOfSendMessageThreads;

                        // create handlerthread and run task on there
                        HandlerThread ht = new HandlerThread("sendFileThread");
                        ht.start();
                        new SendFileThread(cookie, sensorData, sensorName, dataType, deviceType,
                                this, ht.getLooper()).sendEmptyMessage(0);

                    } else {
                        Log.w(TAG, "Maximum number of sensor data transmission threads reached");
                        requeueMessage(sensorName, sensorData, dataType, deviceType, this);
                    }

                } else {
                    // start send thread
                    if (nrOfSendMessageThreads < MAX_NR_OF_SEND_MSG_THREADS) {
                        ++nrOfSendMessageThreads;

                        // create handlerthread and run task on there
                        HandlerThread ht = new HandlerThread("sendDataPointThread");
                        ht.start();
                        new SendDataThread(cookie, sensorData, sensorName, dataType, deviceType,
                                this, ht.getLooper()).sendEmptyMessage(0);

                    } else {
                        Log.w(TAG, "Maximum number of sensor data transmission threads reached");
                        requeueMessage(sensorName, sensorData, dataType, deviceType, this);
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in sending sensor data:", e);
        }
    }
}
