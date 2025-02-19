package io.pslab.fragment;

import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.text.Html;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import io.pslab.R;
import io.pslab.activity.GyroscopeActivity;
import io.pslab.models.GyroData;
import io.pslab.models.SensorDataBlock;
import io.pslab.others.CSVLogger;

import static android.content.Context.SENSOR_SERVICE;

public class GyroscopeDataFragment extends Fragment {

    private static int updatePeriod = 1000;
    private static float highLimit = 1.2f;
    private static float gain = 1;
    private int turns = 0;
    private boolean returningFromPause = false;
    private Timer graphTimer;
    private SensorManager sensorManager;
    private Sensor sensor;
    private long startTime, block;
    private GyroData sensorData;
    private ArrayList<GyroData> recordedGyroArray;
    private GyroscopeActivity gyroSensor;
    private ArrayList<GyroscopeViewFragment> gyroscopeViewFragments = new ArrayList<>();
    private int[] colors = {Color.YELLOW, Color.MAGENTA, Color.GREEN};
    private DecimalFormat df = new DecimalFormat("+#0.0;-#0.0");

    public static GyroscopeDataFragment newInstance() {
        return new GyroscopeDataFragment();
    }

    public static void setParameters(float highLimit, int updatePeriod, String gain) {
        GyroscopeDataFragment.highLimit = highLimit;
        GyroscopeDataFragment.updatePeriod = updatePeriod;
        GyroscopeDataFragment.gain = Integer.valueOf(gain);
    }
    public static Pair<Integer, Pair<Float, Float>> getParameters(){
        return new Pair<>(updatePeriod, new Pair<>(highLimit, gain));
    }
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startTime = System.currentTimeMillis();
        gyroSensor = (GyroscopeActivity) getActivity();
        for (GyroscopeViewFragment fragment : gyroscopeViewFragments){
            fragment.clear();
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_gyroscope_data, container, false);
        gyroscopeViewFragments.clear();
        gyroscopeViewFragments.add((GyroscopeViewFragment)getChildFragmentManager().findFragmentById(R.id.gyroscope_x_axis_fragment));
        gyroscopeViewFragments.add((GyroscopeViewFragment)getChildFragmentManager().findFragmentById(R.id.gyroscope_y_axis_fragment));
        gyroscopeViewFragments.add((GyroscopeViewFragment)getChildFragmentManager().findFragmentById(R.id.gyroscope_z_axis_fragment));

        gyroscopeViewFragments.get(1).getGyroAxisImage().setImageResource(R.drawable.phone_y_axis);
        gyroscopeViewFragments.get(2).getGyroAxisImage().setImageResource(R.drawable.phone_z_axis);

        setupInstruments();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (gyroSensor.playingData) {
            recordedGyroArray = new ArrayList<>();
            resetInstrumentData();
            playRecordedData();
        } else if (gyroSensor.viewingData) {
            recordedGyroArray = new ArrayList<>();
            resetInstrumentData();
            plotAllRecordedData();
        } else if (!gyroSensor.isRecording) {
            updateGraphs();
            initiateBaroSensor();
        } else if (returningFromPause) {
            updateGraphs();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (graphTimer != null) {
            graphTimer.cancel();
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(gyroScopeSensorEventListener);
        }
    }

    private void plotAllRecordedData() {
        recordedGyroArray.addAll(gyroSensor.recordedGyroData);
        if (recordedGyroArray.size() != 0) {
            for (int i = 0; i < gyroscopeViewFragments.size(); i ++) {
                GyroscopeViewFragment fragment = gyroscopeViewFragments.get(i);
                for (GyroData d : recordedGyroArray) {
                    if (fragment.getCurrentMax() < d.getGyro()[i]) {
                         fragment.setCurrentMax(d.getGyro()[i]);
                    }
                    if (fragment.getCurrentMin() < d.getGyro()[i]) {
                        fragment.setCurrentMin(d.getGyro()[i]);
                    }
                    Entry entry = new Entry((float) (d.getTime() - d.getBlock()) / 1000, d.getGyro()[i]);
                    fragment.addEntry(entry);
                }

                fragment.setYaxis();

                LineDataSet dataSet = new LineDataSet(fragment.getEntries(), getString(R.string.gyroscope));
                dataSet.setDrawCircles(false);
                dataSet.setDrawValues(false);
                dataSet.setLineWidth(2);

                dataSet.setDrawCircles(false);
                dataSet.setDrawValues(false);
                dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
                dataSet.setLineWidth(1);
                dataSet.setColor(colors[i]);
                LineData data = new LineData(dataSet);
                fragment.setChartData(data);
            }
        }
    }

    private void playRecordedData() {
        recordedGyroArray.addAll(gyroSensor.recordedGyroData);
        try {
            if (recordedGyroArray.size() > 1) {
                GyroData i = recordedGyroArray.get(1);
                long timeGap = i.getTime() - i.getBlock();
                processRecordedData(timeGap);
            } else {
                processRecordedData(0);
            }
        } catch (IllegalArgumentException e) {
            Toast.makeText(getActivity(),
                    getActivity().getResources().getString(R.string.no_data_fetched), Toast.LENGTH_SHORT).show();
        }
    }
 private void processRecordedData(long timeGap) {
        final Handler handler = new Handler();
        if (graphTimer != null) {
            graphTimer.cancel();
        } else {
            graphTimer = new Timer();
        }
        graphTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gyroSensor.playingData) {
                            try {
                                GyroData d = recordedGyroArray.get(turns);
                                turns++;
                                for (int i = 0; i < gyroscopeViewFragments.size(); i ++ ) {
                                    GyroscopeViewFragment fragment = gyroscopeViewFragments.get(i);
                                    StringBuilder builder = new StringBuilder();
                                    builder.append(df.format(d.getGyro()[i]));
                                    builder.append(" ");
                                    builder.append(getResources().getString(R.string.radian_per_sec_text));
                                    fragment.setGyroValue(String.valueOf(Html.fromHtml(builder.toString())));

                                    if (fragment.getCurrentMax() < d.getGyro()[i]) {
                                        fragment.setCurrentMax(d.getGyro()[i]);
                                        StringBuilder max_builder = new StringBuilder();
                                        max_builder.append("Max: ");
                                        max_builder.append(df.format(fragment.getCurrentMax()));
                                        max_builder.append(" ");
                                        max_builder.append(getResources().getString(R.string.radian_per_sec_text));
                                        fragment.setGyroMax(String.valueOf(Html.fromHtml(max_builder.toString())));
                                    }
                                    if (fragment.getCurrentMin() < d.getGyro()[i]) {
                                        fragment.setCurrentMin(d.getGyro()[i]);
                                        StringBuilder min_builder = new StringBuilder();
                                        min_builder.append("Min: ");
                                        min_builder.append(df.format(fragment.getCurrentMax()));
                                        min_builder.append(" ");
                                        min_builder.append(getResources().getString(R.string.radian_per_sec_text));
                                        fragment.setGyroMin(String.valueOf(Html.fromHtml(min_builder.toString())));
                                    }


                                    fragment.setYaxis();
                                    Entry entryX = new Entry((float) (d.getTime() - d.getBlock()) / 1000, d.getGyro()[i]);
                                    fragment.addEntry(entryX);

                                    LineDataSet dataSet = new LineDataSet(fragment.getEntries(), getString(R.string.gyroscope));
                                    dataSet.setDrawCircles(false);
                                    dataSet.setDrawValues(false);
                                    dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
                                    dataSet.setLineWidth(1);
                                    dataSet.setColor(colors[i]);
                                    LineData data = new LineData(dataSet);

                                    fragment.setChartData(data);
                                }
                            } catch (IndexOutOfBoundsException e) {
                                graphTimer.cancel();
                                graphTimer = null;
                                turns = 0;
                                gyroSensor.playingData = false;
                                gyroSensor.startedPlay = false;
                                gyroSensor.invalidateOptionsMenu();
                            }
                        }
                    }
                });
            }
        }, 0, timeGap);
    }
    public void playData() {
        resetInstrumentData();
        gyroSensor.startedPlay = true;
        try {
            if (recordedGyroArray.size() > 1) {
                GyroData i = recordedGyroArray.get(1);
                long timeGap = i.getTime() - i.getBlock();
                processRecordedData(timeGap);
            } else {
                processRecordedData(0);
            }
        } catch (IllegalArgumentException e) {
            Toast.makeText(getActivity(),
                    getActivity().getResources().getString(R.string.no_data_fetched), Toast.LENGTH_SHORT).show();
        }
    }

    public void stopData() {
        if (graphTimer != null) {
            graphTimer.cancel();
            graphTimer = null;
        }
        recordedGyroArray.clear();
        for (GyroscopeViewFragment fragment : gyroscopeViewFragments) {
            fragment.clearEntry();
        }
        plotAllRecordedData();
        gyroSensor.startedPlay = false;
        gyroSensor.playingData = false;
        turns = 0;
        gyroSensor.invalidateOptionsMenu();
    }
    public void saveGraph() {
        // Todo: Save graph view to gallery
    }
    private void setupInstruments() {
        for (GyroscopeViewFragment fragment : gyroscopeViewFragments) {
            fragment.setUp();
        }
    }
    @Override
    public void onPause() {
        super.onPause();
        if (graphTimer != null) {
            returningFromPause = true;
            graphTimer.cancel();
            graphTimer = null;
            if (gyroSensor.playingData) {
                gyroSensor.finish();
            }
        }
    }
//
    private void updateGraphs() {
        final Handler handler = new Handler();
        if (graphTimer != null) {
            graphTimer.cancel();
        }
        graphTimer = new Timer();
        graphTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            visualizeData();
                        } catch (NullPointerException e) {
                            /* Pass for another refresh round */
                        }
                    }
                });
            }
        }, 0, updatePeriod);
    }
    private void writeLogToFile(long timestamp, float readingX, float readingY, float readingZ) {
        if (getActivity() != null && gyroSensor.isRecording) {
            if (gyroSensor.writeHeaderToFile) {
                gyroSensor.csvLogger.prepareLogFile();
                gyroSensor.csvLogger.writeCSVFile("Timestamp,DateTime,ReadingsX,ReadingsY,ReadingsZ,Latitude,Longitude");
                block = timestamp;
                gyroSensor.recordSensorDataBlockID(new SensorDataBlock(timestamp, gyroSensor.getSensorName()));
                gyroSensor.writeHeaderToFile = !gyroSensor.writeHeaderToFile;
            }
            if (gyroSensor.addLocation && gyroSensor.gpsLogger.isGPSEnabled()) {
                String dateTime = CSVLogger.FILE_NAME_FORMAT.format(new Date(timestamp));
                Location location = gyroSensor.gpsLogger.getDeviceLocation();
                gyroSensor.csvLogger.writeCSVFile(timestamp + "," + dateTime + ","
                        + readingX + "," + readingY + "," + readingZ + "," + location.getLatitude() + "," + location.getLongitude());
                sensorData = new GyroData(timestamp, block, gyroscopeViewFragments.get(0).getCurrentValue(), gyroscopeViewFragments.get(1).getCurrentValue(), gyroscopeViewFragments.get(2).getCurrentValue(), location.getLatitude(), location.getLongitude());
            } else {
                String dateTime = CSVLogger.FILE_NAME_FORMAT.format(new Date(timestamp));
                gyroSensor.csvLogger.writeCSVFile(timestamp + "," + dateTime + ","
                        + readingX + "," + readingY + "," + readingZ + "," + ",0.0,0.0");
                sensorData = new GyroData(timestamp, block, gyroscopeViewFragments.get(0).getCurrentValue(), gyroscopeViewFragments.get(1).getCurrentValue(), gyroscopeViewFragments.get(2).getCurrentValue(), 0.0, 0.0);
            }
            gyroSensor.recordSensorData(sensorData);
        } else {
            gyroSensor.writeHeaderToFile = true;
        }
    }
    private void visualizeData() {
        for (int i = 0; i < gyroscopeViewFragments.size(); i ++) {
            GyroscopeViewFragment fragment = gyroscopeViewFragments.get(i);
            long timeElapsed = (System.currentTimeMillis() - startTime) / 1000;
            if (timeElapsed != fragment.getPreviousTimeElapsed()) {
                fragment.setPreviousTimeElapsed(timeElapsed);
                fragment.addEntry(new Entry((float) timeElapsed, fragment.getCurrentValue()));

                LineDataSet dataSet = new LineDataSet(fragment.getEntries(), getString(R.string.gyroscope));
                dataSet.setDrawCircles(false);
                dataSet.setDrawValues(false);
                dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
                dataSet.setLineWidth(1);
                dataSet.setColor(colors[i]);
                LineData data = new LineData(dataSet);

                fragment.setChartData(data);
                fragment.setYaxis();
            }
        }
        Long currentTime = System.currentTimeMillis();
        writeLogToFile(currentTime, gyroscopeViewFragments.get(0).getCurrentValue(), gyroscopeViewFragments.get(1).getCurrentValue(), gyroscopeViewFragments.get(2).getCurrentValue());
    }
    private SensorEventListener gyroScopeSensorEventListener = new SensorEventListener() {

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {/**/}

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                for (int i = 0; i < gyroscopeViewFragments.size(); i ++) {
                    GyroscopeViewFragment fragment = gyroscopeViewFragments.get(i);
                    fragment.setCurrentValue(event.values[i]);
                    StringBuilder builder = new StringBuilder();
                    builder.append(df.format(fragment.getCurrentValue()));
                    builder.append(" ");
                    builder.append(getResources().getString(R.string.radian_per_sec_text));
                    fragment.setGyroValue(String.valueOf(Html.fromHtml(builder.toString())));

                    if (fragment.getCurrentValue() > fragment.getCurrentMax()) {
                        builder.insert(0, getResources().getString(R.string.text_max));
                        builder.insert(3, " ");
                        fragment.setGyroMax(String.valueOf(Html.fromHtml(builder.toString())));
                        fragment.setCurrentMax(fragment.getCurrentValue());
                    }
                    else if (fragment.getCurrentValue() < fragment.getCurrentMin()) {
                        builder.insert(0, getResources().getString(R.string.text_min));
                        builder.insert(3, " ");
                        fragment.setGyroMin(String.valueOf(Html.fromHtml(builder.toString())));
                        fragment.setCurrentMin(fragment.getCurrentValue());
                    }
                }
            }
        }
    };
    private void resetInstrumentData() {
        for (GyroscopeViewFragment fragment : gyroscopeViewFragments){
            fragment.clear();
        }
    }

    private void initiateBaroSensor() {
        resetInstrumentData();
        sensorManager = (SensorManager) getContext().getSystemService(SENSOR_SERVICE);
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (sensor == null) {
            Toast.makeText(getContext(), getResources().getString(R.string.no_gyroscope_sensor), Toast.LENGTH_LONG).show();
        } else {
            sensorManager.registerListener(gyroScopeSensorEventListener,
                    sensor, SensorManager.SENSOR_DELAY_FASTEST);
        }

    }
}
