const createStoragePlugin = require('../plugins/venjs-plugin-storage');
venjs.use(createStoragePlugin(), { fileName: 'app_storage.json' });

const App = () => venjs.div({ style: { padding: '16' } }, [
  venjs.text({ textContent: 'venjsX Tooling Demo' }),
  venjs.button({
    textContent: 'Create + read file',
    onClick: async () => {
      await venjs.createFile({ name: 'example.txt', write: 'Hello this is an example' });
      const { read } = await venjs.readFile({ name: 'example.txt' });
      alert(read);
    }
  }),
  venjs.button({
    textContent: 'Storage: increment counter',
    onClick: async () => {
      const current = await venjs.storage.get('counter', 0);
      const next = Number(current || 0) + 1;
      await venjs.storage.set('counter', next);
      alert(`counter=${next}`);
    }
  }),
  venjs.button({
    textContent: 'Get location',
    onClick: async () => {
      try {
        const loc = await venjs.getLocation({ enableHighAccuracy: true, timeoutMs: 15000 });
        alert(`lat=${loc.latitude}\nlon=${loc.longitude}\nacc=${loc.accuracy}`);
      } catch (e) {
        alert(`Location error: ${e.message}`);
      }
    }
  }),
  venjs.button({
    textContent: 'Double tap me',
    onDoubleTap: () => alert('Double tap!')
  }),
  venjs.text({ textContent: 'Shake the phone to trigger a handler (see console).' })
]);

venjs.onShake(() => {
  console.log('shake detected');
});

venjs.mount(App);
