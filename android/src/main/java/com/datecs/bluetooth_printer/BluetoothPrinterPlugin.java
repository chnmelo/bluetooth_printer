package com.datecs.bluetooth_printer;

import androidx.annotation.NonNull;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

import com.datecs.api.BuildInfo;
import com.datecs.api.emsr.EMSR;
import com.datecs.api.printer.Printer;
import com.datecs.api.printer.Printer.ConnectionListener;
import com.datecs.api.printer.PrinterInformation;
import com.datecs.api.printer.ProtocolAdapter;
import com.datecs.api.rfid.ContactlessCard;
import com.datecs.api.rfid.FeliCaCard;
import com.datecs.api.rfid.ISO14443Card;
import com.datecs.api.rfid.ISO15693Card;
import com.datecs.api.rfid.RC663;
import com.datecs.api.rfid.RC663.CardListener;
import com.datecs.api.rfid.STSRICard;
import com.datecs.api.universalreader.UniversalReader;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static android.provider.Settings.System.getString;

/**
 * BluetoothPrinterPlugin
 */
public class BluetoothPrinterPlugin implements MethodCallHandler, RequestPermissionsResultListener {

    private static final String TAG = "BThermalPrinterDPP";
    private static final String NAMESPACE = "bluetooth_printer_datecs";
    private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1451;
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final Registrar registrar;
    private static ConnectedThread THREAD = null;
    private BluetoothAdapter mBluetoothAdapter;

    private static final String LOG_TAG = "PrinterSample";

    private Result pendingResult;

    private EventSink readSink;
    private EventSink statusSink;

    private ProtocolAdapter.Channel mPrinterChannel;
    private ProtocolAdapter mProtocolAdapter;
    private Printer mPrinter;
    private BluetoothSocket mBtSocket;

    private StringBuffer textBuffer;

    public static final int ALIGN_CENTER = Printer.ALIGN_CENTER;
    public static final int ALIGN_LEFT = Printer.ALIGN_LEFT;
    public static final int ALIGN_RIGHT = Printer.ALIGN_RIGHT;

    public static void registerWith(Registrar registrar) {
        final BluetoothPrinterPlugin instance = new BluetoothPrinterPlugin(registrar);
        registrar.addRequestPermissionsResultListener(instance);
    }

    BluetoothPrinterPlugin(Registrar registrar) {
        this.registrar = registrar;
        MethodChannel channel = new MethodChannel(registrar.messenger(), NAMESPACE + "/methods");
        EventChannel stateChannel = new EventChannel(registrar.messenger(), NAMESPACE + "/state");
        EventChannel readChannel = new EventChannel(registrar.messenger(), NAMESPACE + "/read");
        if (registrar.activity() != null) {
            BluetoothManager mBluetoothManager = (BluetoothManager) registrar.activity()
                    .getSystemService(Context.BLUETOOTH_SERVICE);
            assert mBluetoothManager != null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                this.mBluetoothAdapter = mBluetoothManager.getAdapter();
            }
        }
        channel.setMethodCallHandler(this);
        stateChannel.setStreamHandler(stateStreamHandler);
        readChannel.setStreamHandler(readResultsHandler);
    }

    // Interface, used to invoke asynchronous printer operation.
    private interface PrinterRunnable {
        public void run(ProgressDialog dialog, Printer printer) throws IOException;
    }

    protected void initPrinter(InputStream inputStream, OutputStream outputStream)
            throws IOException {
        Log.d(LOG_TAG, "Initialize printer...");

        // Here you can enable various debug information
        //ProtocolAdapter.setDebug(true);
        Printer.setDebug(true);
        EMSR.setDebug(true);

        // Check if printer is into protocol mode. Ones the object is created it can not be released
        // without closing base streams.
        mProtocolAdapter = new ProtocolAdapter(inputStream, outputStream);
        if (mProtocolAdapter.isProtocolEnabled()) {
            // Get printer instance
            mPrinterChannel = mProtocolAdapter.getChannel(ProtocolAdapter.CHANNEL_PRINTER);
            mPrinter = new Printer(mPrinterChannel.getInputStream(), mPrinterChannel.getOutputStream());
            THREAD = new ConnectedThread(mPrinter);
        }
    }

    @TargetApi(Build.VERSION_CODES.ECLAIR)
    private void connect(Result result, String address) {
        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(LOG_TAG, "Connecting to " + address + "...");

                btAdapter.cancelDiscovery();

                try {
                    UUID uuid = MY_UUID;
                    BluetoothDevice btDevice = btAdapter.getRemoteDevice(address);

                    InputStream in = null;
                    OutputStream out = null;

                    try {
                        BluetoothSocket btSocket = btDevice.createRfcommSocketToServiceRecord(uuid);
                        btSocket.connect();

                        mBtSocket = btSocket;
                        in = mBtSocket.getInputStream();
                        out = mBtSocket.getOutputStream();
                    } catch (IOException e) {

                        result.error("Problema ao conectar com o Bluetooth", e.toString(), null);
                        return;
                    }

                    try {
                        initPrinter(in, out);
                        result.success(true);
                    } catch (IOException e) {
                        result.error("Problema ao conectar com o Bluetooth", e.toString(), null);
                        return;
                    }
                } finally {
                }
            }
        });
        t.start();
    }

    private synchronized void closeBluetoothConnection() {
        // Close Bluetooth connection
        BluetoothSocket s = mBtSocket;
        mBtSocket = null;
        if (s != null) {
            Log.d(LOG_TAG, "Close Bluetooth socket");
            try {
                s.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // MethodChannel.Result wrapper that responds on the platform thread.
    private static class MethodResultWrapper implements Result {
        private Result methodResult;
        private Handler handler;

        MethodResultWrapper(Result result) {
            methodResult = result;
            handler = new Handler(Looper.getMainLooper());
        }

        @Override
        public void success(final Object result) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    methodResult.success(result);
                }
            });
        }

        @Override
        public void error(final String errorCode, final String errorMessage, final Object errorDetails) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    methodResult.error(errorCode, errorMessage, errorDetails);
                }
            });
        }

        @Override
        public void notImplemented() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    methodResult.notImplemented();
                }
            });
        }
    }

    public void reset(Result result) {
        try {
            THREAD.reset();
            result.success(true);
        } catch (Exception error) {
            result.error("Problema ao Imprimir linha", error.toString(), null);
        }
    }

    public void setAlign(Result result, String align) {
        try {
            THREAD.setAlign(align.toUpperCase());
            result.success(true);
        } catch (Exception error) {
            result.error("Problema ao Imprimir linha", error.toString(), null);
        }
    }

    public void feedPaper(Result result, int lines) {
        try {
            THREAD.feedPaper(lines);
            result.success(true);
        } catch (Exception error) {
            result.error("Problema ao Imprimir linha", error.toString(), null);
        }
    }

    public void printText(Result result, String message) {
        try {
            THREAD.printText(message);
            result.success(true);
        } catch (Exception error) {
            result.error("Problema ao Imprimir linha", error.toString(), null);
        }
    }

    public void printImage(Result result, byte[] bytes, int width, int height,String align,
                                     boolean dither, boolean crop) {
        try {
            THREAD.printImage(bytes,width,height,align,dither,crop);
            result.success(true);
        } catch (Exception error) {
            result.error("Problema ao Imprimir linha", error.toString(), null);
        }
    }

    public void printImage(Result result, byte[] bytes, int width, int height,String align,
                                     boolean dither) {
        try {
            THREAD.printImage(bytes,width,height,align,dither);
            result.success(true);
        } catch (Exception error) {
            result.error("Problema ao Imprimir linha", error.toString(), null);
        }
    }

    private void printImageBytes(Result result, byte[] bytes) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                byte[] command = Utils.decodeBitmap(bmp);
                THREAD.write(command);
            } else {
                Log.e("Print Photo error", "the file isn't exists");
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_image_error", ex.getMessage(), null);
        }
    }

    public void printCompressedImage(Result result, byte[] bytes, int width, int height,String align,
                                     boolean dither, boolean crop) {
        try {
            THREAD.printCompressedImage(bytes,width,height,align,dither,crop);
            result.success(true);
        } catch (Exception error) {
            result.error("Problema ao Imprimir linha", error.toString(), null);
        }
    }

    public void printCompressedImage(Result result, byte[] bytes, int width, int height,String align,
                                     boolean dither) {
        try {
            THREAD.printCompressedImage(bytes,width,height,align,dither);
            result.success(true);
        } catch (Exception error) {
            result.error("Problema ao Imprimir linha", error.toString(), null);
        }
    }

    public void printTaggedText(Result result, String message) {
        try {
            THREAD.printTaggedText(message);
            result.success(true);
        } catch (Exception error) {
            result.error("Problema ao Imprimir linha", error.toString(), null);
        }
    }

    public void flush(Result result) {
        try {
            THREAD.flush();
            result.success(true);
        } catch (Exception error) {
            result.error("Problema ao Imprimir linha", error.toString(), null);
        }
    }

    @TargetApi(Build.VERSION_CODES.ECLAIR)
    @Override
    public void onMethodCall(MethodCall call, Result rawResult) {
        Result result = new MethodResultWrapper(rawResult);

        if (mBluetoothAdapter == null && !"isAvailable".equals(call.method)) {
            result.error("bluetooth_unavailable", "the device does not have bluetooth", null);
            return;
        }

        final Map<String, Object> arguments = call.arguments();

        switch (call.method) {

            case "isAvailable":
                result.success(mBluetoothAdapter != null);
                break;

            case "isConnected":
                result.success(THREAD != null);
                break;

            case "connect":
                if (arguments.containsKey("address")) {
                    String address = (String) arguments.get("address");
                    connect(result, address);
                } else {
                    result.error("invalid_argument", "argument 'address' not found", null);
                }
                break;

            case "getBondedDevices":
                try {

                    if (ContextCompat.checkSelfPermission(registrar.activity(),
                            Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                        ActivityCompat.requestPermissions(registrar.activity(),
                                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_COARSE_LOCATION_PERMISSIONS);

                        pendingResult = result;
                        break;
                    }

                    getBondedDevices(result);

                } catch (Exception ex) {
                    result.error("Error", ex.getMessage(), null);
                }

                break;

            case "printText":
                if (arguments.containsKey("message")) {
                    String message = (String) arguments.get("message");
                    printText(result, message);
                } else {
                    result.error("invalid_argument", "argument 'message' not found", null);
                }
                break;

            case "printTaggedText":
                if (arguments.containsKey("message")) {
                    String message = (String) arguments.get("message");
                    printTaggedText(result, message);
                } else {
                    result.error("invalid_argument", "argument 'message' not found", null);
                }
                break;

            case "printImageBytes":
                if (arguments.containsKey("bytes")) {
                    byte[] bytes = (byte[]) arguments.get("bytes");
                    printImageBytes(result, bytes);
                } else {
                    result.error("invalid_argument", "argument 'bytes' not found", null);
                }
                break;

            case "printImage":
                if (arguments.containsKey("bytes")) {
                    byte[] bytes = (byte[]) arguments.get("bytes");
                    int height = (int) arguments.get("height");
                    int width = (int) arguments.get("width");
                    String align = (String) arguments.get("align");
                    boolean dither = (boolean) arguments.get("dither");
                    boolean crop = (boolean) arguments.get("crop");

                    printImage(result, bytes, width, height, align, dither, crop);
                } else {
                    result.error("invalid_argument", "argument 'message' not found", null);
                }
                break;

            case "printCompressedImage":
                if (arguments.containsKey("bytes")) {
                    byte[] bytes = (byte[]) arguments.get("bytes");
                    int height = (int) arguments.get("height");
                    int width = (int) arguments.get("width");
                    String align = (String) arguments.get("align");
                    boolean dither = (boolean) arguments.get("dither");
                    boolean crop = (boolean) arguments.get("crop");

                    printCompressedImage(result, bytes, width, height, align, dither, crop);
                } else {
                    result.error("invalid_argument", "argument 'message' not found", null);
                }
                break;

            case "feedPaper":

                if (arguments.containsKey("lines")) {
                    int lines = (int) arguments.get("lines");
                    feedPaper(result, lines);
                } else {
                    result.error("invalid_argument", "argument 'message' not found", null);
                }
                break;

            case "setAlign":
                if (arguments.containsKey("align")) {
                    String align = (String) arguments.get("align");
                    setAlign(result, align);
                } else {
                    result.error("invalid_argument", "argument 'message' not found", null);
                }
                break;

            case "reset":
                reset(result);
                break;

            case "flush":
                flush(result);
                break;

            default:
                result.notImplemented();
                break;
        }
    }


    /**
     * @param requestCode  requestCode
     * @param permissions  permissions
     * @param grantResults grantResults
     * @return boolean
     */
    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {

        if (requestCode == REQUEST_COARSE_LOCATION_PERMISSIONS) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getBondedDevices(pendingResult);
            } else {
                pendingResult.error("no_permissions", "this plugin requires location permissions for scanning", null);
                pendingResult = null;
            }
            return true;
        }
        return false;
    }

    /**
     * @param result result
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void getBondedDevices(Result result) {

        List<Map<String, Object>> list = new ArrayList<>();

        for (BluetoothDevice device : mBluetoothAdapter.getBondedDevices()) {
            Map<String, Object> ret = new HashMap<>();
            ret.put("address", device.getAddress());
            ret.put("name", device.getName());
            ret.put("type", device.getType());
            list.add(ret);
        }

        result.success(list);
    }

    private class ConnectedThread extends Thread {
        private Printer printer;

        ConnectedThread(Printer printer) {
            this.printer = printer;
        }

        private int getAlign(String align) {
            switch (align) {
                case "CENTER":
                    return Printer.ALIGN_CENTER;
                case "LEFT":
                    return Printer.ALIGN_LEFT;
                case "RIGHT":
                    return Printer.ALIGN_RIGHT;
                default:
                    return Printer.ALIGN_LEFT;
            }
        }

        public void write(byte[] bytes) {
            try {
                printer.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void reset() {
            try {
                printer.reset();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void setAlign(String align) {
            try {
                printer.setAlign(getAlign(align.toUpperCase()));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void feedPaper(int lines) {
            try {
                printer.feedPaper(lines);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private Bitmap scaleDown(Bitmap realImage, float maxImageSize,
                                       boolean filter) {
            float ratio = Math.min(
                    (float) maxImageSize / realImage.getWidth(),
                    (float) maxImageSize / realImage.getHeight());
            int width = Math.round((float) ratio * realImage.getWidth());
            int height = Math.round((float) ratio * realImage.getHeight());

            Bitmap newBitmap = Bitmap.createScaledBitmap(realImage, width,
                    height, filter);
            return newBitmap;
        }

        public void printImage(byte[] bytes, int width, int height, String align,
                                         boolean dither) {
            try {
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes , 0, bytes.length);
                final Bitmap bitmap = Bitmap.createScaledBitmap(bmp, width, height, true);
                final int[] argb = new int[width * height];
                bitmap.getPixels(argb, 0, width, 0, 0, width, height);
                bitmap.recycle();
                printer.printImage(argb, width, height, getAlign(align.toUpperCase()),
                        dither);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void printImage(byte[] bytes, int width, int height, String align,
                                         boolean dither, boolean crop) {
            try {
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes , 0, bytes.length);
                final Bitmap bitmap = Bitmap.createScaledBitmap(bmp, width, height, true);
                final int[] argb = new int[width * height];
                bitmap.getPixels(argb, 0, width, 0, 0, width, height);
                bitmap.recycle();
                printer.printImage(argb, width, height, getAlign(align.toUpperCase()),
                        dither, crop);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void printCompressedImage(byte[] bytes, int width, int height, String align,
                                         boolean dither) {
            try {
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes , 0, bytes.length);
                final Bitmap bitmap = Bitmap.createScaledBitmap(bmp, width, height, true);
                final int[] argb = new int[width * height];
                bitmap.getPixels(argb, 0, width, 0, 0, width, height);
                bitmap.recycle();
                printer.printCompressedImage(argb, width, height, getAlign(align.toUpperCase()),
                        dither);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void printCompressedImage(byte[] bytes, int width, int height, String align,
                                         boolean dither, boolean crop) {
            try {
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes , 0, bytes.length);
                final Bitmap bitmap = Bitmap.createScaledBitmap(bmp, width, height, true);
                final int[] argb = new int[width * height];
                bitmap.getPixels(argb, 0, width, 0, 0, width, height);
                bitmap.recycle();
                printer.printCompressedImage(argb, width, height, getAlign(align.toUpperCase()),
                        dither, crop);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        public void printText(String message) {
            try {
                printer.printText(message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void printTaggedText(String message) {
            try {
                printer.printTaggedText(message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void flush() {
            try {
                printer.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private final StreamHandler stateStreamHandler = new StreamHandler() {

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                Log.d(TAG, action);

                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    statusSink.success(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    statusSink.success(1);
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    statusSink.success(0);
                }
            }
        };

        @Override
        public void onListen(Object o, EventSink eventSink) {
            statusSink = eventSink;
            registrar.activity().registerReceiver(mReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

            registrar.activeContext().registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));

            registrar.activeContext().registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));
        }

        @Override
        public void onCancel(Object o) {
            statusSink = null;
            registrar.activity().unregisterReceiver(mReceiver);
        }
    };

    private final StreamHandler readResultsHandler = new StreamHandler() {
        @Override
        public void onListen(Object o, EventSink eventSink) {
            readSink = eventSink;
        }

        @Override
        public void onCancel(Object o) {
            readSink = null;
        }
    };
}
