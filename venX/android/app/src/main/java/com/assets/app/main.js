/**
 * VenX Developer Workspace
 * Path: android/app/src/main/assets/app/main.js
 * * This is the primary entry point for the mobile application.
 * Using venX.createElement sends JSON instructions to the Native Engine 
 * (Java/Swift) to render high-performance native components.
 */

const App = () => {
    // We define our UI using the venX.createElement API
    return venX.createElement('div', { 
        style: { 
            padding: '24', 
            backgroundColor: '#FFFFFF',
            flex: 1,
            justifyContent: 'center'
        } 
    }, [
        // This renders a Native TextView on Android / UILabel on iOS
        venX.createElement('text', { 
            textContent: 'Welcome to VenX Native',
            style: { 
                fontSize: '28', 
                fontWeight: 'bold', 
                color: '#111827',
                textAlign: 'center',
                marginBottom: '16'
            } 
        }),

        // This renders a Native TextView
        venX.createElement('text', { 
            textContent: 'You are writing JavaScript that drives actual Java and Swift components. No DOM involved.',
            style: { 
                fontSize: '16', 
                color: '#4B5563',
                textAlign: 'center',
                lineHeight: '24'
            } 
        }),

        // This renders a Native Button
        venX.createElement('button', { 
            textContent: 'Explore Native Features',
            style: { 
                marginTop: '32', 
                backgroundColor: '#2563EB', 
                color: '#FFFFFF',
                borderRadius: '12',
                padding: '16'
            },
            // Events are captured and processed by the Native Bridge
            events: {
                click: () => {
                    console.log("Native Button Tapped!");
                }
            }
        })
    ]);
};

/**
 * venX.mount
 * Since we are in a mobile bridge, the first argument is null 
 * because there is no 'document.getElementById' in a native environment.
 */
venX.mount(null, App);