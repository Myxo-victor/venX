package com.venx;

import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.venx.core.VenXEngine;

/**
 * MainActivity for the VenX Framework.
 * This class initializes the native UI container and the JavaScript bridge.
 */
public class MainActivity extends AppCompatActivity {
    private WebView bridge;
    private VenXEngine engine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Initialize the Native Root Container
        // This is where the VenXEngine will inject native Java views
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);

        // 2. Setup the JavaScript Bridge (Hidden WebView)
        // We use a WebView to execute the JS logic, but it's not visible to the user
        bridge = new WebView(this);
        bridge.getSettings().setJavaScriptEnabled(true);
        bridge.getSettings().setAllowFileAccess(true);
        bridge.getSettings().setDomStorageEnabled(true);
        
        // Prevent redirects from opening in external browsers
        bridge.setWebViewClient(new WebViewClient());

        // 3. Initialize VenX Engine and connect the bridge
        // The engine translates the JSON from JS into actual Android Views
        engine = new VenXEngine(this, root);
        bridge.addJavascriptInterface(engine, "Android");

        // 4. Load the Developer's Entry Point
        // This points to the assets folder where the user's JS code lives
        bridge.loadUrl("file:///android_asset/app/index.html");
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.destroy();
        }
        super.onDestroy();
    }
}