import UIKit
import WebKit

/**
 * VenXEngine for iOS
 * Translates JSON UI instructions into pure Swift UIViews.
 */
class VenXEngine: NSObject, WKScriptMessageHandler {
    var rootViewController: UIViewController?
    var containerView: UIView?

    init(controller: UIViewController, container: UIView) {
        self.rootViewController = controller
        self.containerView = container
    }

    // Listens for messages from the JS Bridge
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        if message.name == "processUINode", let jsonString = message.body as? String {
            if let data = jsonString.data(using: .utf8) {
                do {
                    if let json = try JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] {
                        DispatchQueue.main.async {
                            self.updateUI(with: json)
                        }
                    }
                } catch {
                    print("VenX Error: \(error)")
                }
            }
        }
    }

    private func updateUI(with node: [String: Any]) {
        guard let container = containerView else { return }
        container.subviews.forEach { $0.removeFromSuperview() }
        
        let nativeView = renderNode(node: node)
        nativeView.frame = container.bounds
        nativeView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(nativeView)
    }

    private func renderNode(node: [String: Any]) -> UIView {
        let tag = node["tag"] as? String ?? "div"
        let props = node["props"] as? [String: Any] ?? [:]
        
        var view: UIView
        
        switch tag {
        case "button":
            let btn = UIButton(type: .system)
            btn.setTitle(props["textContent"] as? String ?? "", for: .normal)
            view = btn
        case "text":
            let label = UILabel()
            label.text = props["textContent"] as? String ?? ""
            label.textAlignment = .center
            view = label
        default:
            let stack = UIStackView()
            stack.axis = .vertical
            stack.spacing = 10
            view = stack
        }

        if let children = node["children"] as? [[String: Any]] {
            for child in children {
                let childView = renderNode(node: child)
                if let stack = view as? UIStackView {
                    stack.addArrangedSubview(childView)
                } else {
                    view.addSubview(childView)
                }
            }
        }
        
        return view
    }
}