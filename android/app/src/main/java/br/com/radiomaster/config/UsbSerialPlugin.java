package br.com.radiomaster.config;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "UsbSerial")
public class UsbSerialPlugin extends Plugin implements SerialInputOutputManager.Listener {

    private static final String TAG = "UsbSerialPlugin";
    private static final String ACTION_USB_PERMISSION = "br.com.radiomaster.config.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbSerialPort openPort = null;
    private SerialInputOutputManager ioManager;
    private List<UsbSerialDriver> availableDrivers = new ArrayList<>();

    @Override
    public void load() {
        usbManager = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
        Log.d(TAG, "UsbSerialPlugin loaded");

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        Log.d(TAG, "USB devices connected on load: " + deviceList.size());
        for (UsbDevice device : deviceList.values()) {
            Log.d(TAG, "  Device: " + device.getDeviceName()
                  + " VID:" + String.format("%04X", device.getVendorId())
                  + " PID:" + String.format("%04X", device.getProductId()));
        }
    }

    @PluginMethod
    public void listPorts(PluginCall call) {
        availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        Log.d(TAG, "listPorts: found " + availableDrivers.size() + " serial devices");
        JSArray ports = new JSArray();
        for (int i = 0; i < availableDrivers.size(); i++) {
            UsbSerialDriver driver = availableDrivers.get(i);
            UsbDevice device = driver.getDevice();
            JSObject port = new JSObject();
            port.put("portId", i);
            port.put("vendorId", device.getVendorId());
            port.put("productId", device.getProductId());
            port.put("deviceName", device.getDeviceName());
            port.put("driverClass", driver.getClass().getSimpleName());
            ports.put(port);
        }
        JSObject result = new JSObject();
        result.put("ports", ports);
        call.resolve(result);
    }

    @PluginMethod
    public void requestPort(PluginCall call) {
        availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        Log.d(TAG, "requestPort: found " + availableDrivers.size() + " serial devices");

        if (availableDrivers.isEmpty()) {
            call.reject("NotFoundError: No USB serial device found. Connect via USB OTG cable.");
            return;
        }

        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDevice device = driver.getDevice();
        Log.d(TAG, "requestPort: device VID=" + String.format("%04X", device.getVendorId())
              + " PID=" + String.format("%04X", device.getProductId()));

        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "requestPort: permission already granted");
            resolveRequestPortCall(call, device, 0);
        } else {
            Log.d(TAG, "requestPort: requesting permission from user");

            // Explicit intent required for Android 14+ (API 34+)
            Intent intent = new Intent(ACTION_USB_PERMISSION);
            intent.setPackage(getContext().getPackageName());

            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                getContext(), 0, intent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );

            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            getContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                        synchronized (this) {
                            UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                Log.d(TAG, "requestPort: permission granted");
                                UsbDevice resolved = (usbDevice != null) ? usbDevice : device;
                                resolveRequestPortCall(call, resolved, 0);
                            } else {
                                Log.d(TAG, "requestPort: permission denied");
                                call.reject("USB permission denied by user");
                            }
                            context.unregisterReceiver(this);
                        }
                    }
                }
            }, filter, Context.RECEIVER_NOT_EXPORTED);

            usbManager.requestPermission(device, permissionIntent);
        }
    }

    private void resolveRequestPortCall(PluginCall call, UsbDevice device, int portId) {
        JSObject result = new JSObject();
        result.put("portId", portId);
        result.put("vendorId", device.getVendorId());
        result.put("productId", device.getProductId());
        result.put("deviceName", device.getDeviceName());
        call.resolve(result);
    }

    @PluginMethod
    public void open(PluginCall call) {
        int portId = call.getInt("portId", 0);
        int baudRate = call.getInt("baudRate", 460800);
        Log.d(TAG, "open: portId=" + portId + " baudRate=" + baudRate);

        if (availableDrivers.isEmpty()) {
            availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        }

        if (portId >= availableDrivers.size()) {
            call.reject("Invalid portId: " + portId + " (only " + availableDrivers.size() + " devices found)");
            return;
        }

        UsbSerialDriver driver = availableDrivers.get(portId);
        UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
        if (connection == null) {
            call.reject("Could not open USB connection. Permission may have been revoked.");
            return;
        }

        openPort = driver.getPorts().get(0);
        try {
            openPort.open(connection);
            openPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            openPort.setDTR(true);
            openPort.setRTS(true);
            Log.d(TAG, "open: port opened successfully at " + baudRate + " baud");
        } catch (IOException e) {
            Log.e(TAG, "open: failed - " + e.getMessage());
            call.reject("Failed to open port: " + e.getMessage());
            openPort = null;
            return;
        }

        ioManager = new SerialInputOutputManager(openPort, this);
        Executors.newSingleThreadExecutor().submit(ioManager);

        JSObject result = new JSObject();
        result.put("success", true);
        result.put("baudRate", baudRate);
        call.resolve(result);
    }

    @PluginMethod
    public void write(PluginCall call) {
        String dataBase64 = call.getString("data", "");
        if (openPort == null) {
            call.reject("Port not open");
            return;
        }
        try {
            byte[] bytes = Base64.decode(dataBase64, Base64.DEFAULT);
            Log.d(TAG, "write: " + bytes.length + " bytes");
            openPort.write(bytes, 2000);
            call.resolve();
        } catch (IOException e) {
            Log.e(TAG, "write: failed - " + e.getMessage());
            call.reject("Write failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void close(PluginCall call) {
        Log.d(TAG, "close called");
        stopIoManager();
        if (openPort != null) {
            try { openPort.close(); } catch (IOException ignored) {}
            openPort = null;
        }
        call.resolve();
    }

    @PluginMethod
    public void setBaudRate(PluginCall call) {
        int baudRate = call.getInt("baudRate", 460800);
        if (openPort == null) {
            call.reject("Port not open");
            return;
        }
        try {
            openPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            Log.d(TAG, "setBaudRate: " + baudRate);
            call.resolve();
        } catch (IOException e) {
            call.reject("Failed to set baud rate: " + e.getMessage());
        }
    }

    private void stopIoManager() {
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
    }

    // SerialInputOutputManager.Listener
    @Override
    public void onNewData(byte[] data) {
        Log.d(TAG, "onNewData: " + data.length + " bytes");
        JSObject event = new JSObject();
        event.put("data", Base64.encodeToString(data, Base64.DEFAULT));
        notifyListeners("data", event);
    }

    @Override
    public void onRunError(Exception e) {
        Log.e(TAG, "onRunError: " + e.getMessage());
        stopIoManager();
        if (openPort != null) {
            try { openPort.close(); } catch (IOException ignored) {}
            openPort = null;
        }
        JSObject event = new JSObject();
        event.put("reason", e.getMessage());
        notifyListeners("disconnected", event);
    }
}
