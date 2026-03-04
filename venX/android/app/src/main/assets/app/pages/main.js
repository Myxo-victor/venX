const username = venjs.state('');
const taps = venjs.state(0);

const App = () =>
  venjs.div({ className: 'app-container' }, [
    venjs.text({
      textContent: 'venjsX Phase 5 Demo',
      className: 'app-title'
    }),
    venjs.text({
      textContent: `Hello ${username.get() || 'Developer'}`,
      className: 'app-subtitle'
    }),
    venjs.input({
      placeholder: 'Type your name',
      value: username.get(),
      className: 'app-input',
      onChange: (payload) => {
        username.set(payload.value || '');
      }
    }),
    venjs.image({
      src: './images/dev.jpg',
      className: 'app-image'
    }),
    venjs.activityIndicator({
      className: 'app-spinner'
    }),
    venjs.button({
      textContent: `Tap Count: ${taps.get()}`,
      className: 'app-button',
      onClick: () => {
        taps.set((current) => current + 1);
      }
    })
  ]);

venjs.mount(App);