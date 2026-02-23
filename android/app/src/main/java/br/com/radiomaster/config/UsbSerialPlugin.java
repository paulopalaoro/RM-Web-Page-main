package br.com.radiomaster.config;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Base64;

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
import java.util.Map;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "UsbSerial")
public class UsbSerialPlugin extends Plugin implements SerialInputOutputManager.Listener {

    private static final String ACTION_USB_PERMISSION = "br.com.radiomaster.config.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbSerialPort openPort = null;
    private SerialInputOutputManager ioManager;
    private PluginCall pendingRequestPortCall = null;
    private PluginCall pendingOpenCall = null;
    private List<UsbSerialDriver> availableDrivers = new ArrayList<>();

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (pendingRequestPortCall != null && device != null) {
                            resolveRequestPort(device);
                        }
                    } else {
                        if (pendingRequestPortCall != null) {
                            pendingRequestPortCall.reject("USB permission denied by user");
                            pendingRequestPortCall = null;
                        }
                    }
                }
            }
        }
    };

    @Override
    public void load() {
        usbManager = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(usbPermissionReceiver, filter);
        }
    }

    @PluginMethod
    public void listPorts(PluginCall call) {
        availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
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
        if (availableDrivers.isEmpty()) {
            call.reject("NotFoundError: No USB serial device found. Connect the device via USB OTG cable.");
            return;
        }

        // If only one device, request permission directly
        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDevice device = driver.getDevice();

        if (usbManager.hasPermission(device)) {
            resolveRequestPortWithCall(call, device);
        } else {
            pendingRequestPortCall = call;
            bridge.saveCall(call);
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                getContext(), 0,
                new Intent(ACTION_USB_PERMISSION),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT
            );
            usbManager.requestPermission(device, permissionIntent);
        }
    }

    private void resolveRequestPort(UsbDevice device) {
        if (pendingRequestPortCall != null) {
            resolveRequestPortWithCall(pendingRequestPortCall, device);
            pendingRequestPortCall = null;
        }
    }

    private void resolveRequestPortWithCall(PluginCall call, UsbDevice device) {
        int portId = -1;
        for (int i = 0; i < availableDrivers.size(); i++) {
            if (availableDrivers.get(i).getDevice().equals(device)) {
                portId = i;
                break;
            }
        }
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

        if (portId >= availableDrivers.size()) {
            call.reject("Invalid portId");
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
        } catch (IOException e) {
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
            openPort.write(bytes, 2000);
            call.resolve();
        } catch (IOException e) {
            call.reject("Write failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void close(PluginCall call) {
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
        JSObject event = new JSObject();
        event.put("data", Base64.encodeToString(data, Base64.DEFAULT));
        notifyListeners("data", event);
    }

    @Override
    public void onRunError(Exception e) {
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
