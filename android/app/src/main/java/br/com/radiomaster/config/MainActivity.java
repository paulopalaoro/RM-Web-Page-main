package br.com.radiomaster.config;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(UsbSerialPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
