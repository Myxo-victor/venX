/**
 * VenX Developer Workspace (iOS)
 * Path: ios/app/main.js
 * * This is the primary entry point for the iOS version of your app.
 * The venX engine on iOS will execute this code and translate 
 * these elements into Native Apple UIKit components.
 */

const App = () => {
    return venX.createElement('div', { 
        style: { 
            padding: '30', 
            backgroundColor: '#F9FAFB',
            flex: 1,
            justifyContent: 'center'
        } 
    }, [
        // Renders as a Native UILabel
        venX.createElement('text', { 
            textContent: 'VenX iOS Native',
            style: { 
                fontSize: '32', 
                fontWeight: 'bold', 
                color: '#007AFF', // Classic iOS Blue
                textAlign: 'center',
                marginBottom: '20'
            } 
        }),

        // Renders as a Native UILabel
        venX.createElement('text', { 
            textContent: 'This interface is rendered using pure Swift UIKit components, driven by JavaScript logic.',
            style: { 
                fontSize: '18', 
                color: '#3A3A3C',
                textAlign: 'center',
                lineHeight: '26'
            } 
        }),

        // Renders as a Native UIButton
        venX.createElement('button', { 
            textContent: 'iOS Native Action',
            style: { 
                marginTop: '40', 
                backgroundColor: '#007AFF', 
                color: '#FFFFFF',
                borderRadius: '12',
                padding: '18'
            },
            events: {
                click: () => {
                    // This is sent to VenXEngine.swift via WKScriptMessageHandler
                    console.log("iOS Native Button Pressed");
                }
            }
        })
    ]);
};

// Mount to the iOS Root View
// The first argument is null because iOS uses a native container, not a DOM element.
venX.mount(null, App);