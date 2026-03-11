function safeParseJson(text, fallback) {
  if (typeof text !== 'string') return fallback;
  try {
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === 'object' ? parsed : fallback;
  } catch (_err) {
    return fallback;
  }
}

function createStoragePlugin() {
  return {
    name: 'venjs-plugin-storage',
    install: (venjs, options = {}) => {
      const fileName = typeof options.fileName === 'string' && options.fileName.trim()
        ? options.fileName.trim()
        : 'venjs_storage.json';

      const state = {
        loaded: false,
        data: {},
        writeChain: Promise.resolve()
      };

      async function ensureLoaded() {
        if (state.loaded) return;
        state.loaded = true;
        try {
          const res = await venjs.readFile({ name: fileName });
          state.data = safeParseJson(res && res.read, {}) || {};
        } catch (_err) {
          try {
            await venjs.createFile({ name: fileName, write: '{}' });
          } catch (_err2) {
          }
          state.data = {};
        }
      }

      function enqueueWrite(nextData) {
        state.data = nextData;
        state.writeChain = state.writeChain.then(async () => {
          await venjs.writeFile({ name: fileName, write: JSON.stringify(state.data) });
        });
        return state.writeChain;
      }

      venjs.storage = {
        get: async (key, defaultValue = null) => {
          await ensureLoaded();
          const k = String(key);
          return Object.prototype.hasOwnProperty.call(state.data, k) ? state.data[k] : defaultValue;
        },

        set: async (key, value) => {
          await ensureLoaded();
          const k = String(key);
          const next = { ...state.data, [k]: value };
          await enqueueWrite(next);
          return true;
        },

        remove: async (key) => {
          await ensureLoaded();
          const k = String(key);
          if (!Object.prototype.hasOwnProperty.call(state.data, k)) return true;
          const next = { ...state.data };
          delete next[k];
          await enqueueWrite(next);
          return true;
        },

        clear: async () => {
          await ensureLoaded();
          await enqueueWrite({});
          return true;
        },

        keys: async () => {
          await ensureLoaded();
          return Object.keys(state.data);
        }
      };
    }
  };
}

module.exports = createStoragePlugin;

