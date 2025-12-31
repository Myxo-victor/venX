/**
 * VenX Native Core (v1.0.0)
 * Part of the VenX Project by Myxo Victor @ Aximon
 * * This library provides the developer API for building Native UI
 * using JavaScript. It bypasses the DOM and communicates directly
 * with the Java (Android) or Swift (iOS) native engines.
 */

const venX = {
    /**
     * Creates a Virtual Node representation of a native component.
     * @param {string} tag - The native component type (div, text, button, image)
     * @param {Object} props - Properties like textContent, style, etc.
     * @param {Array} children - Nested venX elements
     */
    createElement: (tag, props = {}, children = []) => {
        return {
            tag: tag,
            props: props,
            children: children.map(child => 
                typeof child === 'string' ? { tag: 'text', props: { textContent: child } } : child
            )
        };
    },

    /**
     * Internal bridge to transmit the UI Tree to the Native Layer.
     */
    _transmit: (uiTree) => {
        const payload = JSON.stringify(uiTree);
        
        // Android Detection (JavascriptInterface)
        if (window.Android && window.Android.processUINode) {
            window.Android.processUINode(payload);
        } 
        // iOS Detection (WKScriptMessageHandler)
        else if (window.webkit && window.webkit.messageHandlers.processUINode) {
            window.webkit.messageHandlers.processUINode.postMessage(payload);
        } 
        else {
            console.warn("VenX: No native engine detected. Rendering to console for debugging:");
            console.dir(uiTree);
        }
    },

    /**
     * Mounts a VenX component to the native root view.
     * @param {Function} component - A function returning a venX element tree.
     */
    mount: (component) => {
        const uiTree = component();
        venX._transmit(uiTree);
    }
};

// Export for module-based environments
if (typeof module !== 'undefined') {
    module.exports = venX;
} else {
    window.venX = venX;
}