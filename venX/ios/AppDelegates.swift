import UIKit
import WebKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?
    var webView: WKWebView!
    var venXEngine: VenXEngine!

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        
        window = UIWindow(frame: UIScreen.main.bounds)
        let rootVC = UIViewController()
        rootVC.view.backgroundColor = .white
        window?.rootViewController = rootVC
        window?.makeKeyAndVisible()

        // 1. Initialize the Bridge configuration
        let contentController = WKUserContentController()
        let config = WKWebViewConfiguration()
        config.userContentController = contentController

        // 2. Setup the Hidden JS Bridge (WebView)
        webView = WKWebView(frame: .zero, configuration: config)
        
        // 3. Initialize the VenX Engine
        // It renders into the Root View Controller's view
        venXEngine = VenXEngine(container: rootVC.view, viewController: rootVC)
        
        // 4. Register the message handler (This connects to venX.js _transmit)
        contentController.add(venXEngine, name: "processUINode")

        // 5. Load the Developer App
        if let url = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "app") {
            webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }

        return true
    }
}