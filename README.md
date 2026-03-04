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

## Phase 5 Runtime Features
- Native click events are routed back into JS handlers via `onClick` / `onPress` / `events.click`.
- Native input change events are routed via `onChange` / `events.change`.
- Reactive state updates are supported with `venjs.state(...).set(...)`, which auto-rerenders.
- Basic style mapping is enabled on Android + iOS for:
`backgroundColor`, `padding`, `margin*`, `fontSize`, `fontWeight`, `fontFamily`, `color`, `textAlign`, `borderRadius`, `flexDirection`, `alignItems`, `gap`, `width`, `height`
- Supported native tags:
`div`, `text`, `button`, `input`, `checkbox`, `icon`, `image`, `activityIndicator`

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
