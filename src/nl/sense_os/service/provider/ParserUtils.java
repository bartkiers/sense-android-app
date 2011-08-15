package nl.sense_os.service.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import nl.sense_os.service.provider.SensorData.DataPoint;
import android.util.Log;

public class ParserUtils {

    private static final String TAG = "ParserUtils";

    /**
     * Tries to parse the selection String to see which data has to be returned for the query. Looks
     * for occurrences of "sensor_name" in the selection String.
     * 
     * @param allSensors
     *            Set of all possible sensors, used to form the selection from.
     * @param selection
     *            Selection string from the query.
     * @param selectionArgs
     *            Selection arguments. Not used yet.
     * @return List of sensor names that are included in the query.
     */
    public static List<String> getSelectedSensors(Set<String> allSensors, String selection,
            String[] selectionArgs) {

        List<String> names = new ArrayList<String>();

        // preprocess the selection string a bit
        selection = selection.replaceAll(" = ", "=");
        selection = selection.replaceAll("= ", "=");
        selection = selection.replaceAll(" =", "=");
        selection = selection.replaceAll(" != ", "!=");
        selection = selection.replaceAll("!= ", "!=");
        selection = selection.replaceAll(" !=", "!=");

        if (selection != null && selection.contains(DataPoint.SENSOR_NAME)) {

            int eqKeyStart = selection.indexOf(DataPoint.SENSOR_NAME + "='");
            int neqKeyStart = selection.indexOf(DataPoint.SENSOR_NAME + "!='")
                    + (DataPoint.SENSOR_NAME + "!='").length();

            if (-1 != eqKeyStart) {
                // selection contains "sensor_name='"
                int sensorNameStart = eqKeyStart + (DataPoint.SENSOR_NAME + "='").length();
                int sensorNameEnd = selection.indexOf("'", sensorNameStart);
                sensorNameEnd = sensorNameEnd == -1 ? selection.length() - 1 : sensorNameEnd;
                String sensorName = selection.substring(sensorNameStart, sensorNameEnd);
                if (sensorName.equals("?")) {
                    throw new IllegalArgumentException(
                            "LocalStorage cannot handle queries with arguments array, sorry...");
                }
                // Log.v(TAG, "Query contains: " + DataPoint.SENSOR_NAME + " = '" + sensorName +
                // "'");

                names.add(sensorName);

            } else if (-1 != neqKeyStart) {
                // selection contains "sensor_name!='"
                int sensorNameStart = neqKeyStart + (DataPoint.SENSOR_NAME + "!='").length();
                int sensorNameEnd = selection.indexOf("'", sensorNameStart);
                sensorNameEnd = sensorNameEnd == -1 ? selection.length() - 1 : sensorNameEnd;
                String notSensorName = selection.substring(sensorNameStart, sensorNameEnd);
                if (notSensorName.equals("?")) {
                    throw new IllegalArgumentException(
                            "LocalStorage cannot handle queries with arguments array, sorry...");
                }
                // Log.v(TAG, "Query contains: " + DataPoint.SENSOR_NAME + " != '" + notSensorName +
                // "'");

                for (String name : allSensors) {
                    if (!name.equals(notSensorName)) {
                        names.add(name);
                    }
                }
            } else {
                Log.w(TAG, "Parser cannot handle selection query: " + selection);
            }

        } else {
            // no selection: return all sensor names
            names.addAll(allSensors);
        }

        return names;
    }

    /**
     * Tries to parse the selection String to see which data has to be returned for the query. Looks
     * for occurrences of "timestamp" in the selection String.
     * 
     * @param selection
     *            Selection string from the query.
     * @param selectionArgs
     *            Selection arguments. Not used yet.
     * @return Array with minimum and maximum time stamp for the query result.
     */
    public static long[] getSelectedTimeRange(String selection, String[] selectionArgs) {

        long minTimestamp = Long.MIN_VALUE;
        long maxTimestamp = Long.MAX_VALUE;

        // preprocess the selection string a bit
        selection = selection.replaceAll(" = ", "=");
        selection = selection.replaceAll("= ", "=");
        selection = selection.replaceAll(" =", "=");
        selection = selection.replaceAll(" > ", ">");
        selection = selection.replaceAll("> ", ">");
        selection = selection.replaceAll(" >", ">");
        selection = selection.replaceAll(" < ", "<");
        selection = selection.replaceAll("< ", "<");
        selection = selection.replaceAll(" <", "<");
        selection = selection.replaceAll(" != ", "!=");
        selection = selection.replaceAll("!= ", "!=");
        selection = selection.replaceAll(" !=", "!=");

        if (selection != null && selection.contains(DataPoint.TIMESTAMP)) {

            int eqKeyStart = selection.indexOf(DataPoint.TIMESTAMP + "=");
            int neqKeyStart = selection.indexOf(DataPoint.TIMESTAMP + "!=");
            int leqKeyStart = selection.indexOf(DataPoint.TIMESTAMP + "<=");
            int ltKeyStart = selection.indexOf(DataPoint.TIMESTAMP + "<");
            int geqKeyStart = selection.indexOf(DataPoint.TIMESTAMP + ">=");
            int gtKeyStart = selection.indexOf(DataPoint.TIMESTAMP + ">");

            if (-1 != eqKeyStart) {
                // selection contains "timestamp='"
                int timestampStart = eqKeyStart + (DataPoint.TIMESTAMP + "=").length();
                int timestampEnd = selection.indexOf(" ", timestampStart);
                timestampEnd = timestampEnd == -1 ? selection.length() : timestampEnd;
                String timestamp = selection.substring(timestampStart, timestampEnd);
                if (timestamp.equals("?")) {
                    throw new IllegalArgumentException(
                            "LocalStorage cannot handle queries with arguments array, sorry...");
                }
                // Log.v(TAG, "Query contains: " + DataPoint.TIMESTAMP + " = " + timestamp);

                minTimestamp = maxTimestamp = Long.parseLong(timestamp);

            } else if (-1 != neqKeyStart) {
                // selection contains "timestamp!='"
                int timestampStart = neqKeyStart + (DataPoint.TIMESTAMP + "!=").length();
                int timestampEnd = selection.indexOf(" ", timestampStart);
                timestampEnd = timestampEnd == -1 ? selection.length() : timestampEnd;
                String timestamp = selection.substring(timestampStart, timestampEnd);
                if (timestamp.equals("?")) {
                    throw new IllegalArgumentException(
                            "LocalStorage cannot handle queries with arguments array, sorry...");
                }
                // Log.v(TAG, "Query contains: " + DataPoint.TIMESTAMP + " != " + timestamp);

                // use default timestamps

            } else if (-1 != geqKeyStart) {
                // selection contains "timestamp>='"
                int timestampStart = geqKeyStart + (DataPoint.TIMESTAMP + ">=").length();
                int timestampEnd = selection.indexOf(" ", timestampStart);
                timestampEnd = timestampEnd == -1 ? selection.length() : timestampEnd;
                String timestamp = selection.substring(timestampStart, timestampEnd);
                if (timestamp.equals("?")) {
                    throw new IllegalArgumentException(
                            "LocalStorage cannot handle queries with arguments array, sorry...");
                }
                // Log.v(TAG, "Query contains: " + DataPoint.TIMESTAMP + " >= " + timestamp);

                minTimestamp = Long.parseLong(timestamp);

            } else if (-1 != gtKeyStart) {
                // selection contains "timestamp>'"
                int timestampStart = gtKeyStart + (DataPoint.TIMESTAMP + ">").length();
                int timestampEnd = selection.indexOf(" ", timestampStart);
                timestampEnd = timestampEnd == -1 ? selection.length() : timestampEnd;
                String timestamp = selection.substring(timestampStart, timestampEnd);
                if (timestamp.equals("?")) {
                    throw new IllegalArgumentException(
                            "LocalStorage cannot handle queries with arguments array, sorry...");
                }
                // Log.v(TAG, "Query contains: " + DataPoint.TIMESTAMP + " > " + timestamp);

                minTimestamp = Long.parseLong(timestamp) - 1;

            } else if (-1 != leqKeyStart) {
                // selection contains "timestamp<='"
                int timestampStart = leqKeyStart + (DataPoint.TIMESTAMP + "<=").length();
                int timestampEnd = selection.indexOf(" ", timestampStart);
                timestampEnd = timestampEnd == -1 ? selection.length() : timestampEnd;
                String timestamp = selection.substring(timestampStart, timestampEnd);
                if (timestamp.equals("?")) {
                    throw new IllegalArgumentException(
                            "LocalStorage cannot handle queries with arguments array, sorry...");
                }
                // Log.v(TAG, "Query contains: " + DataPoint.TIMESTAMP + " <= " + timestamp);

                maxTimestamp = Long.parseLong(timestamp);

            } else if (-1 != ltKeyStart) {
                // selection contains "timestamp<'"
                int timestampStart = ltKeyStart + (DataPoint.TIMESTAMP + "<").length();
                int timestampEnd = selection.indexOf(" ", timestampStart);
                timestampEnd = timestampEnd == -1 ? selection.length() : timestampEnd;
                String timestamp = selection.substring(timestampStart, timestampEnd);
                if (timestamp.equals("?")) {
                    throw new IllegalArgumentException(
                            "LocalStorage cannot handle queries with arguments array, sorry...");
                }
                // Log.v(TAG, "Query contains: " + DataPoint.TIMESTAMP + " < " + timestamp);

                maxTimestamp = Long.parseLong(timestamp) - 1;

            } else {
                Log.w(TAG, "Parser cannot handle selection query: " + selection);
            }

        } else {
            // no selection: return all times
            return new long[] { Long.MIN_VALUE, Long.MAX_VALUE };
        }

        return new long[] { minTimestamp, maxTimestamp };
    }

    private ParserUtils() {
        // class should not be instantiated
    }
}