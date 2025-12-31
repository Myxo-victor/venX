/**
 * VenX Example Gallery
 * These snippets demonstrate different UI patterns using the VenX Native Engine.
 */

// 1. SIMPLE COUNTER (State Management Example)
const CounterApp = () => {
    // In a real VenX app, you'd use a state store, 
    // but here is the structural representation.
    return venX.createElement('div', { style: { padding: '40', alignItems: 'center' } }, [
        venX.createElement('text', { 
            textContent: 'Counter Example',
            style: { fontSize: '24', marginBottom: '20' } 
        }),
        venX.createElement('text', { 
            textContent: '0', 
            style: { fontSize: '60', fontWeight: 'bold', color: '#2563EB' } 
        }),
        venX.createElement('button', { 
            textContent: 'Increment',
            style: { marginTop: '20', backgroundColor: '#2563EB', color: '#FFF', padding: '15' }
        })
    ]);
};

// 2. PROFILE CARD (Layout & Styling Example)
const ProfileCard = () => {
    return venX.createElement('div', { 
        style: { margin: '20', padding: '20', borderRadius: '15', backgroundColor: '#FFF', shadow: 'true' } 
    }, [
        venX.createElement('div', { style: { flexDirection: 'row', alignItems: 'center' } }, [
            venX.createElement('div', { 
                style: { width: '60', height: '60', borderRadius: '30', backgroundColor: '#DDD' } 
            }),
            venX.createElement('div', { style: { marginLeft: '15' } }, [
                venX.createElement('text', { 
                    textContent: 'Myxo Victor', 
                    style: { fontSize: '20', fontWeight: 'bold' } 
                }),
                venX.createElement('text', { 
                    textContent: 'Founder of Aximon', 
                    style: { fontSize: '14', color: '#666' } 
                })
            ])
        ]),
        venX.createElement('text', { 
            textContent: 'Building the future of cross-platform native development with VenX.',
            style: { marginTop: '15', lineHeight: '20' } 
        })
    ]);
};

// 3. LOGIN SCREEN (Input & Form Example)
const LoginScreen = () => {
    return venX.createElement('div', { style: { padding: '30', flex: 1, justifyContent: 'center' } }, [
        venX.createElement('text', { 
            textContent: 'Welcome Back', 
            style: { fontSize: '30', fontWeight: 'bold', marginBottom: '40' } 
        }),
        venX.createElement('div', { style: { marginBottom: '20' } }, [
            venX.createElement('text', { textContent: 'Email Address', style: { marginBottom: '5' } }),
            venX.createElement('div', { style: { borderBottom: '1', borderColor: '#CCC', height: '40' } })
        ]),
        venX.createElement('div', { style: { marginBottom: '40' } }, [
            venX.createElement('text', { textContent: 'Password', style: { marginBottom: '5' } }),
            venX.createElement('div', { style: { borderBottom: '1', borderColor: '#CCC', height: '40' } })
        ]),
        venX.createElement('button', { 
            textContent: 'Login',
            style: { backgroundColor: '#000', color: '#FFF', padding: '18', borderRadius: '10' }
        })
    ]);
};

// Exporting examples for the developer to switch between
const Examples = {
    Counter: CounterApp,
    Profile: ProfileCard,
    Login: LoginScreen
};