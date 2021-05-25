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
/** BluetoothPrinterPlugin */
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

  private void printNewLine(Result result, StringBuffer textBuffer) {
    textBuffer.append("{br}");
    result.success(true);
  }

  private void writeLine(Result result, StringBuffer textBuffer, String message) {
    textBuffer.append(message);
    result.success(true);
  }

  private void writeTaggedText(Result result, StringBuffer textBuffer, String message) {
    textBuffer.append(message);
    result.success(true);
  }

  private void writeLine(Result result, StringBuffer textBuffer, String message, String align) {
    textBuffer.append("{reset}{"+align.toLowerCase()+"}"+message+"{br}");
    result.success(true);
  }

  private void writeBoldLine(Result result, StringBuffer textBuffer, String message, String align) {
    textBuffer.append("{reset}{"+align.toLowerCase()+"}{b}"+message+"{br}");
    result.success(true);
  }

  private void writeItalicLine(Result result, StringBuffer textBuffer, String message, String align) {
    textBuffer.append("{reset}{"+align.toLowerCase()+"}{i}"+message+"{br}");
    result.success(true);
  }

  private void writeUndelineLine(Result result, StringBuffer textBuffer, String message, String align) {
    textBuffer.append("{reset}{"+align.toLowerCase()+"}{u}"+message+"{br}");
    result.success(true);
  }

  private void writeBigFontLine(Result result, StringBuffer textBuffer, String message, String align) {
    textBuffer.append("{reset}{"+align.toLowerCase()+"}{h}"+message+"{br}");
    result.success(true);
  }

  private void writeCustomLine(Result result, StringBuffer textBuffer, String message, boolean bold,
                               boolean underline, boolean italic, String lenghtText, String align) {
    textBuffer.append("{reset}{"+align.toLowerCase()+"}"+(bold?"{b}":"")+(underline?"{u}":"")+(italic?"{i}":"")+"{"+lenghtText+"}"+message+"{br}");
    result.success(true);
  }

  private void print(Result result, Printer printer ){
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          printer.reset();
          printer.printTaggedText(textBuffer.toString());
          printer.feedPaper(110);
          printer.flush();
          result.success(true);
        } catch (IOException e) {
          e.printStackTrace();
          result.error("error to write", e.toString(), null);
        } catch (Exception e) {
          e.printStackTrace();
          result.error("error to write", e.toString(), null);
        }
      }
    });
    t.start();
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
        result.success(mPrinter != null);
        break;

      case "start":
        textBuffer = new StringBuffer();
        break;

      case "printNewLine":
        printNewLine(result, textBuffer);
        break;

      case "print":
        print(result, mPrinter);
        break;

      case "connect":
        if (arguments.containsKey("address")) {
          String address = (String) arguments.get("address");
          connect(result, address);
        } else {
          result.error("invalid_argument", "argument 'address' not found", null);
        }
        break;
      case "writeLine":
        if (arguments.containsKey("message")) {
          String message = (String) arguments.get("message");
          String align = (String) arguments.get("align");
          writeLine(result, textBuffer, message, align);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;


      case "writeTaggedText":
        if (arguments.containsKey("message")) {
          String message = (String) arguments.get("message");
          String align = (String) arguments.get("align");
          writeTaggedText(result, textBuffer, message);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;


      case "writeBoldLine":
        if (arguments.containsKey("message")) {
          String message = (String) arguments.get("message");
          String align = (String) arguments.get("align");
          writeBoldLine(result, textBuffer, message, align);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;

      case "writeItalicLine":
        if (arguments.containsKey("message")) {
          String message = (String) arguments.get("message");
          String align = (String) arguments.get("align");
          writeItalicLine(result, textBuffer, message, align);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;

      case "writeUndelineLine":
        if (arguments.containsKey("message")) {
          String message = (String) arguments.get("message");
          String align = (String) arguments.get("align");
          writeUndelineLine(result, textBuffer, message, align);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;

      case "writeBigFontLine":
        if (arguments.containsKey("message")) {
          String message = (String) arguments.get("message");
          String align = (String) arguments.get("align");
          writeBigFontLine(result, textBuffer, message, align);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;

      case "writeCustomLine":
        if (arguments.containsKey("message")) {
          String message = (String) arguments.get("message");
          boolean b = (boolean) arguments.get("bold");
          boolean u = (boolean) arguments.get("underline");
          boolean i = (boolean) arguments.get("italic");
          String l = (String) arguments.get("fontSize");
          String align = (String) arguments.get("align");
          writeCustomLine(result, textBuffer, message, b, u, i, l, align);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
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
          result.error("Error", ex.getMessage(),null);
        }

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
    private final BluetoothSocket mmSocket;
    private final InputStream inputStream;
    private final OutputStream outputStream;

    @TargetApi(Build.VERSION_CODES.ECLAIR)
    ConnectedThread(BluetoothSocket socket) {
      mmSocket = socket;
      InputStream tmpIn = null;
      OutputStream tmpOut = null;

      try {
        tmpIn = socket.getInputStream();
        tmpOut = socket.getOutputStream();
      } catch (IOException e) {
        e.printStackTrace();
      }
      inputStream = tmpIn;
      outputStream = tmpOut;
    }

    public void run() {
      byte[] buffer = new byte[1024];
      int bytes;
      while (true) {
        try {
          bytes = inputStream.read(buffer);
          readSink.success(new String(buffer, 0, bytes));
        } catch (NullPointerException e) {
          break;
        } catch (IOException e) {
          break;
        }
      }
    }

    public void write(byte[] bytes) {
      try {
        outputStream.write(bytes);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    public void cancel() {
      try {
        outputStream.flush();
        outputStream.close();

        inputStream.close();

        mmSocket.close();
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
