VenX: 
Pure Native UI Library
VenX is a revolutionary library that allows developers to write application UI in JavaScript using a React-like syntax, while rendering actual Native Components (Java on Android, Swift on iOS).
Repository Structure/js: Contains venX.js (The developer API)./android: Contains VenXEngine.
java (The Java interpreter)./ios: Contains VenXEngine.swift (The Swift interpreter)./examples: Sample implementations.How it WorksDeveloper API: You use venX.createElement to define your UI.
The Bridge: VenX serializes your UI tree into JSON and sends it over a native bridge.
Native Engines: The Java or Swift engine receives the JSON, clears the screen, and creates real UIButton, UITextView, or LinearLayout objects.Getting Started1. Build your UI (JavaScript)const MyApp = () => {
    return venX.createElement('div', {}, [
        venX.createElement('text', { textContent: 'Hello Native World!' }),
        venX.createElement('button', { textContent: 'Click Me' })
    ]);
};

venX.mount(MyApp);
2. Connect to Android (Java)In your MainActivity.java, initialize the engine:VenXEngine engine = new VenXEngine(this, findViewById(R.id.root_container));
webView.addJavascriptInterface(engine, "Android");
3. Connect to iOS (Swift)In your ViewController.swift, add the script handler:let engine = VenXEngine(controller: self, container: self.view)
contentController.add(engine, name: "processUINode")
Why VenX?Unlike traditional cross-platform frameworks, VenX is designed to be lightweight. You clone the repo, drop the engine files into your native project, and start building with JS without needing a massive build system.
Created by Myxo Victor @ Aximon