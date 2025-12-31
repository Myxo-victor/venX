package com.aximon.venx;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * VenXEngine for Android
 * Translates JSON UI instructions into pure Java Android Views.
 */
public class VenXEngine {
    private Context context;
    private ViewGroup rootLayout;

    public VenXEngine(Context context, ViewGroup root) {
        this.context = context;
        this.rootLayout = root;
    }

    /**
     * Main entry point called by the JS Bridge.
     * @param json The serialized UI tree from venX.js
     */
    @android.webkit.JavascriptInterface
    public void processUINode(String json) {
        try {
            JSONObject node = new JSONObject(json);
            // UI updates must happen on the Main Thread
            ((Activity)context).runOnUiThread(() -> {
                rootLayout.removeAllViews();
                rootLayout.addView(renderNode(node));
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private View renderNode(JSONObject node) {
        try {
            String tag = node.getString("tag");
            JSONObject props = node.getJSONObject("props");
            
            View view;
            switch(tag) {
                case "button":
                    Button btn = new Button(context);
                    btn.setText(props.optString("textContent", ""));
                    btn.setAllCaps(false);
                    view = btn;
                    break;
                case "text":
                    TextView tv = new TextView(context);
                    tv.setText(props.optString("textContent", ""));
                    tv.setTextSize(18);
                    view = tv;
                    break;
                case "div":
                default:
                    LinearLayout layout = new LinearLayout(context);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setPadding(20, 20, 20, 20);
                    view = layout;
                    break;
            }

            // Recursively process children
            if (node.has("children") && view instanceof ViewGroup) {
                JSONArray children = node.getJSONArray("children");
                for (int i = 0; i < children.length(); i++) {
                    ((ViewGroup)view).addView(renderNode(children.getJSONObject(i)));
                }
            }
            return view;
        } catch (Exception e) {
            return new View(context);
        }
    }
}