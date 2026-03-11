# venjsX

venjsX is a lightweight cross-platform mobile framework for building native Android and iOS UI with vanilla JavaScript.

## What is included
- Core API: `js/venjsX.js`
- Android runnable shell: `android/`
- iOS runnable shell: `ios/venjsX.xcodeproj`
- JS app examples: `examples/`

## API
- `venjs.createElement(tag, props, children)`
- `venjs.div(props, children)`
- `venjs.text(props, children)`
- `venjs.button(props, children)`
- `venjs.state(initialValue)`
- `venjs.rerender()`
- `venjs.mount(App)`
- `venjs.use(plugin)`
- `venjs.createFile({ name, write, overwrite? })`
- `venjs.listFiles()`
- `venjs.readFile({ name?, path? })`
- `venjs.writeFile({ name?, path?, write, append? })`
- `venjs.getLocation({ enableHighAccuracy?, timeoutMs? })`
- `venjs.onShake(handler) -> unsubscribe`
- `venjs.notifications.requestPermission()`
- `venjs.notifications.scheduleLocal({ id?, title, body, delayMs? | atMs?, data? })`
- `venjs.notifications.cancelLocal({ id })`
- `venjs.notifications.getPushToken()`
- `venjs.notifications.onReceive(handler) -> unsubscribe`
- `venjs.notifications.onTap(handler) -> unsubscribe`

## Phase 5 Runtime Features
- Native click events are routed back into JS handlers via `onClick` / `onPress` / `events.click`.
- Native double-tap events are routed via `onDoubleTap` / `events.doubleTap`.
- Native input change events are routed via `onChange` / `events.change`.
- Reactive state updates are supported with `venjs.state(...).set(...)`, which auto-rerenders.
- Basic style mapping is enabled on Android + iOS for:
`backgroundColor`, `padding`, `margin*`, `fontSize`, `fontWeight`, `fontFamily`, `color`, `textAlign`, `borderRadius`, `flexDirection`, `alignItems`, `gap`, `width`, `height`
- Supported native tags:
`div`, `text`, `button`, `input`, `checkbox`, `icon`, `image`, `activityIndicator`

## Device API examples
### Create + list + read + write files
Files are stored in an app-scoped `venjsX/` directory (Android app files directory / iOS Documents).

```js
await venjs.createFile({ name: 'example.txt', write: 'Hello this is an example' });

const { files } = await venjs.listFiles();
console.log(files);

const { read } = await venjs.readFile({ name: 'example.txt' });
console.log(read);

await venjs.writeFile({ name: 'example.txt', write: '\nAppended line', append: true });
```

### Get user location
```js
try {
  const loc = await venjs.getLocation({ enableHighAccuracy: true, timeoutMs: 15000 });
  console.log(loc.latitude, loc.longitude, loc.accuracy);
} catch (e) {
  console.log('Location error:', e.message);
}
```

### Shake + double tap
```js
const stopShake = venjs.onShake(() => {
  console.log('Device shaken!');
});

const App = () => venjs.div({ style: { padding: '16' } }, [
  venjs.button({
    textContent: 'Double tap me',
    onDoubleTap: () => alert('Double tap!')
  })
]);

venjs.mount(App);
// Later: stopShake();
```

### Notifications (local + push)
**Android 12+ note:** exact alarm scheduling can require the special permission `android.permission.SCHEDULE_EXACT_ALARM`. If it isn’t granted, venjsX falls back to an *inexact* alarm (notification may fire a bit later than the requested time).
```js
await venjs.notifications.requestPermission();

// Local notification in 5 seconds
await venjs.notifications.scheduleLocal({
  id: 'demo-local-1',
  title: 'Hello',
  body: 'This is a local notification',
  delayMs: 5000,
  data: { screen: 'home' }
});

const stopReceive = venjs.notifications.onReceive((n) => console.log('received', n));
const stopTap = venjs.notifications.onTap((n) => console.log('tapped', n));

// Push token (Android: FCM token, iOS: APNs device token)
const { token } = await venjs.notifications.getPushToken();
console.log('push token', token);
```

## Quick example
```js
const App = () => venjs.div({ style: { padding: '16' } }, [
  venjs.text({ textContent: `Count: ${count.get()}` }),
  venjs.button({
    textContent: 'Increment',
    onClick: () => {
      count.set((v) => v + 1);
    }
  })
]);

const count = venjs.state(0);
venjs.mount(App);
```

## Build
- Android: open `android/` in Android Studio.
- iOS: open `ios/venjsX.xcodeproj` in Xcode.

## Tooling (CLI + bundler + live reload)
Tooling is in this folder (`venX/`).

`js/venjsX.js` is the canonical runtime source. The CLI keeps the native shells’ runtime copies in sync automatically:
- Android: `android/app/src/main/assets/js/venjsX.js`
- iOS: `ios/app/js/venjsX.js`

## Official plugins
### `venjs-plugin-storage`
Simple persistent key/value storage built on top of the device file API (stores JSON in an app-scoped file).

```js
const createStoragePlugin = require('./plugins/venjs-plugin-storage');
venjs.use(createStoragePlugin(), { fileName: 'app_storage.json' });

await venjs.storage.set('token', 'abc123');
const token = await venjs.storage.get('token');
console.log(token);
```

### Setup
```bash
npm i
```

### Dev (bundles + syncs + live reload server)
```bash
npm run dev
```

By default the Android emulator tries `http://10.0.2.2:5173/index.html` first, and iOS simulator tries `http://localhost:5173/index.html`, then falls back to local assets if the dev server is not running.

### Build + sync to native shells
```bash
node tools/venjsx.js build --sync
```

### Native logging
```js
venjs.enableNativeLogging();
```

## Push notification setup notes
- Android (FCM):
  - Put `google-services.json` in `android/app/`.
  - Create a Firebase project + enable Cloud Messaging.
  - `venjs.notifications.getPushToken()` returns the FCM token.
  - Your server sends pushes via FCM HTTP v1 to that token.
  - If `google-services.json` is missing, the build skips the Google Services plugin and FCM push won’t work (local notifications still work).
  - Notification icon: set a dedicated small icon (recommended) in `android/app/src/main/AndroidManifest.xml`:
    ```xml
    <meta-data
      android:name="venjsx_notification_small_icon"
      android:resource="@drawable/ic_notification" />
    ```
- iOS (APNs):
  - In Xcode, enable **Push Notifications** capability (and **Background Modes → Remote notifications** if you need background delivery).
  - `venjs.notifications.getPushToken()` returns the APNs device token.
  - Your server sends pushes via APNs HTTP/2 to that device token.
  - iOS notification icon uses the app icon automatically.
