/**
 * venjsX Native Core (v5.0.0)
 * JavaScript API for rendering native mobile UI via Android/iOS bridge engines.
 */

const venjs = {
  _eventHandlers: {},
  _eventHandlerIds: new WeakMap(),
  _eventIdSeed: 1,
  _rootComponent: null,
  _errorHandler: null,
  _nativeEventContext: null,
  _styleRules: [],
  _styleRegistryReady: false,
  _styleReloadWatchInstalled: false,
  _ANIM_TEXT_TAGS: { text: true, button: true, a: true },
  _FA_ICONS: {
    check: '\uf00c',
    close: '\uf00d',
    times: '\uf00d',
    plus: '\uf067',
    minus: '\uf068',
    home: '\uf015',
    user: '\uf007',
    cog: '\uf013',
    settings: '\uf013',
    search: '\uf002',
    trash: '\uf1f8',
    heart: '\uf004',
    star: '\uf005',
    bell: '\uf0f3',
    edit: '\uf044',
    save: '\uf0c7',
    arrowLeft: '\uf060',
    arrowRight: '\uf061'
  },

  _registerEventHandler: (handler) => {
    if (venjs._eventHandlerIds.has(handler)) {
      return venjs._eventHandlerIds.get(handler);
    }
    const id = venjs._eventIdSeed++;
    venjs._eventHandlers[id] = handler;
    venjs._eventHandlerIds.set(handler, id);
    return id;
  },

  _resetEventRegistry: () => {
    venjs._eventHandlers = {};
    venjs._eventHandlerIds = new WeakMap();
    venjs._eventIdSeed = 1;
  },

  _dispatchEvent: (eventId, payload) => {
    const handler = venjs._eventHandlers[eventId];
    if (typeof handler !== 'function') {
      console.warn(`venjsX: No handler found for event id ${eventId}.`);
      return;
    }
    const prevContext = venjs._nativeEventContext;
    venjs._nativeEventContext = payload && payload.type ? String(payload.type) : null;
    try {
      handler(payload);
    } catch (error) {
      console.error('venjsX: Error while executing event handler.', error);
    } finally {
      venjs._nativeEventContext = prevContext;
    }
  },

  _isPropsObject: (value) =>
    value &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    !(value.tag && typeof value.tag === 'string'),

  _normalizeChildren: (children) => {
    const list = Array.isArray(children) ? children : [children];
    const normalized = [];
    list.forEach((child) => {
      if (Array.isArray(child)) {
        normalized.push(...venjs._normalizeChildren(child));
        return;
      }
      if (child === null || child === undefined || child === false) {
        return;
      }
      if (typeof child === 'string' || typeof child === 'number') {
        normalized.push({ tag: 'text', props: { textContent: String(child) }, children: [] });
        return;
      }
      normalized.push(child);
    });
    return normalized;
  },

  _toTextContent: (children) => {
    const normalized = venjs._normalizeChildren(children);
    const text = normalized
      .map((child) => {
        if (child && child.tag === 'text' && child.props && child.props.textContent !== undefined) {
          return String(child.props.textContent);
        }
        return '';
      })
      .join('');
    return text;
  },

  _resolveFaIcon: (name) => {
    if (!name) return '';
    const key = String(name).trim();
    return venjs._FA_ICONS[key] || venjs._FA_ICONS[key.toLowerCase()] || '';
  },

  _toCamelCaseStyleKey: (key) => String(key || '')
    .trim()
    .replace(/-([a-z])/g, (_, c) => c.toUpperCase()),

  _normalizeStyleObject: (styleObj) => {
    const normalized = {};
    if (!styleObj || typeof styleObj !== 'object') {
      return normalized;
    }
    Object.keys(styleObj).forEach((rawKey) => {
      const key = venjs._toCamelCaseStyleKey(rawKey);
      if (!key) return;
      normalized[key] = styleObj[rawKey];
    });
    return normalized;
  },

  _extractStyleDeclarationObject: (styleDecl) => {
    const result = {};
    if (!styleDecl || typeof styleDecl.length !== 'number') {
      return result;
    }
    for (let i = 0; i < styleDecl.length; i += 1) {
      const prop = styleDecl[i];
      if (!prop) continue;
      const value = styleDecl.getPropertyValue(prop);
      if (value === undefined || value === null) continue;
      const normalizedValue = String(value).trim();
      if (!normalizedValue) continue;
      const key = venjs._toCamelCaseStyleKey(prop);
      if (!key) continue;
      result[key] = normalizedValue;
    }
    return result;
  },

  _extractClassSelectorTokens: (selectorText) => {
    const selector = String(selectorText || '').trim();
    if (!selector) return null;
    if (!/^\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$/.test(selector)) {
      return null;
    }
    const parts = selector.split('.').filter(Boolean).map((item) => item.trim()).filter(Boolean);
    return parts.length > 0 ? parts : null;
  },

  _appendRulesFromCssText: (cssText, nextRules, orderRef) => {
    const raw = String(cssText || '');
    if (!raw.trim()) return;

    const cleaned = raw.replace(/\/\*[\s\S]*?\*\//g, '');
    const ruleRegex = /([^{}]+)\{([^{}]*)\}/g;
    let match;
    while ((match = ruleRegex.exec(cleaned)) !== null) {
      const selectorChunk = String(match[1] || '').trim();
      const declarationChunk = String(match[2] || '').trim();
      if (!selectorChunk || !declarationChunk) continue;

      const styleObj = {};
      declarationChunk.split(';').forEach((decl) => {
        const idx = decl.indexOf(':');
        if (idx <= 0) return;
        const prop = decl.slice(0, idx).trim();
        const value = decl.slice(idx + 1).trim();
        if (!prop || !value) return;
        const key = venjs._toCamelCaseStyleKey(prop);
        if (!key) return;
        styleObj[key] = value;
      });
      if (Object.keys(styleObj).length === 0) continue;

      selectorChunk
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean)
        .forEach((selector) => {
          const classes = venjs._extractClassSelectorTokens(selector);
          if (!classes) return;
          orderRef.value += 1;
          nextRules.push({
            classes,
            specificity: classes.length,
            order: orderRef.value,
            style: styleObj
          });
        });
    }
  },

  _loadStylesheetTextByHref: (href) => {
    const target = String(href || '').trim();
    if (!target || typeof XMLHttpRequest === 'undefined') return '';
    try {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', target, false);
      xhr.send(null);
      if ((xhr.status >= 200 && xhr.status < 300) || xhr.status === 0) {
        return xhr.responseText || '';
      }
    } catch (_err) {
    }
    return '';
  },

  _collectDocumentStyleRules: () => {
    if (typeof document === 'undefined' || !document.styleSheets) {
      venjs._styleRules = [];
      venjs._styleRegistryReady = true;
      return;
    }

    const nextRules = [];
    let order = 0;
    const orderRef = { value: 0 };
    Array.from(document.styleSheets).forEach((sheet) => {
      let cssRules = null;
      try {
        cssRules = sheet.cssRules || sheet.rules;
      } catch (_err) {
        cssRules = null;
      }
      if (!cssRules) return;

      Array.from(cssRules).forEach((rule) => {
        if (!rule || rule.type !== 1 || !rule.style || !rule.selectorText) return;
        const styleObj = venjs._extractStyleDeclarationObject(rule.style);
        if (Object.keys(styleObj).length === 0) return;

        const selectors = String(rule.selectorText)
          .split(',')
          .map((item) => item.trim())
          .filter(Boolean);

        selectors.forEach((selector) => {
          const classes = venjs._extractClassSelectorTokens(selector);
          if (!classes) return;
          nextRules.push({
            classes,
            specificity: classes.length,
            order: order += 1,
            style: styleObj
          });
        });
      });
    });

    orderRef.value = order;
    Array.from(document.querySelectorAll('style')).forEach((styleTag) => {
      venjs._appendRulesFromCssText(styleTag.textContent || '', nextRules, orderRef);
    });
    Array.from(document.querySelectorAll('link[rel~="stylesheet"]')).forEach((linkTag) => {
      const href = linkTag.getAttribute('href') || '';
      const cssText = venjs._loadStylesheetTextByHref(href);
      venjs._appendRulesFromCssText(cssText, nextRules, orderRef);
    });

    venjs._styleRules = nextRules;
    venjs._styleRegistryReady = true;
  },

  _ensureStyleRegistry: () => {
    if (venjs._styleRegistryReady) return;
    venjs._collectDocumentStyleRules();
  },

  _resolveClassStyle: (rawClassName) => {
    venjs._ensureStyleRegistry();
    const className = String(rawClassName || '').trim();
    if (!className) return {};

    const active = new Set(
      className
        .split(/\s+/)
        .map((item) => item.trim())
        .filter(Boolean)
    );
    if (active.size === 0) return {};

    const matches = venjs._styleRules
      .filter((rule) => rule.classes.every((cls) => active.has(cls)))
      .sort((a, b) => {
        if (a.specificity !== b.specificity) {
          return a.specificity - b.specificity;
        }
        return a.order - b.order;
      });

    const resolved = {};
    matches.forEach((rule) => {
      Object.assign(resolved, rule.style);
    });
    return resolved;
  },

  _serializeNode: (node) => {
    if (!node || typeof node !== 'object') {
      return node;
    }

    const props = { ...(node.props || {}) };
    const className = [props.className, props.class].filter(Boolean).join(' ').trim();
    const classStyle = venjs._normalizeStyleObject(venjs._resolveClassStyle(className));
    const inlineStyle = props.style && typeof props.style === 'object'
      ? venjs._normalizeStyleObject(props.style)
      : null;
    if (Object.keys(classStyle).length > 0 || inlineStyle) {
      props.style = { ...(classStyle || {}), ...(inlineStyle || {}) };
    }
    delete props.class;
    delete props.className;

    const events = {};
    const sourceEvents = props.events && typeof props.events === 'object' ? props.events : {};

    const clickHandler = typeof props.onClick === 'function'
      ? props.onClick
      : typeof props.onclick === 'function'
        ? props.onclick
        : typeof props.onPress === 'function'
          ? props.onPress
          : null;
    if (clickHandler) {
      events.click = venjs._registerEventHandler(clickHandler);
    }
    if (typeof props.onChange === 'function') {
      events.change = venjs._registerEventHandler(props.onChange);
    }

    Object.keys(sourceEvents).forEach((name) => {
      if (typeof sourceEvents[name] === 'function') {
        events[name] = venjs._registerEventHandler(sourceEvents[name]);
      } else if (typeof sourceEvents[name] === 'number') {
        events[name] = sourceEvents[name];
      }
    });

    delete props.onClick;
    delete props.onclick;
    delete props.onPress;
    delete props.onChange;
    delete props.events;
    if (Object.keys(events).length > 0) {
      props.events = events;
    }

    return {
      tag: node.tag,
      props,
      children: venjs._normalizeChildren(node.children).map((child) => venjs._serializeNode(child))
    };
  },

  _transmit: (uiTree) => {
    const payload = JSON.stringify(uiTree);

    if (window.Android && typeof window.Android.processUINode === 'function') {
      window.Android.processUINode(payload);
      return;
    }

    if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.processUINode) {
      window.webkit.messageHandlers.processUINode.postMessage(payload);
      return;
    }

    console.warn('venjsX: No native engine detected. Rendering to console for debugging.');
    console.dir(uiTree);
  },

  _renderRoot: () => {
    if (typeof venjs._rootComponent !== 'function') {
      const err = new Error('venjsX: no root component mounted. Call venjs.mount(App) first.');
      if (typeof venjs._errorHandler === 'function') {
        venjs._errorHandler(err);
        return;
      }
      throw err;
    }
    try {
      venjs._resetEventRegistry();
      const tree = venjs._rootComponent();
      const serializedTree = venjs._serializeNode(tree);
      venjs._transmit(serializedTree);
    } catch (error) {
      if (typeof venjs._errorHandler === 'function') {
        venjs._errorHandler(error);
        return;
      }
      throw error;
    }
  },

  createElement: (tag, propsOrChildren = {}, ...childrenArgs) => {
    const hasProps = venjs._isPropsObject(propsOrChildren);
    const props = hasProps ? { ...propsOrChildren } : {};

    let rawChildren = hasProps ? childrenArgs : [propsOrChildren, ...childrenArgs];
    if (rawChildren.length === 1 && Array.isArray(rawChildren[0])) {
      rawChildren = rawChildren[0];
    }

    if (venjs._ANIM_TEXT_TAGS[tag] && (props.textContent === undefined || props.textContent === null)) {
      const inferredText = venjs._toTextContent(rawChildren);
      if (inferredText.length > 0) {
        props.textContent = inferredText;
      }
    }

    if (tag === 'icon') {
      props.style = props.style && typeof props.style === 'object' ? { ...props.style } : {};
      if (!props.style.fontFamily) {
        props.style.fontFamily = 'Font Awesome 6 Free';
      }
      if (props.textContent === undefined || props.textContent === null) {
        const glyph = venjs._resolveFaIcon(props.name);
        if (glyph) {
          props.textContent = glyph;
        }
      }
    }

    return {
      tag,
      props,
      children: venjs._normalizeChildren(rawChildren)
    };
  },

  // Shorthand API so developers can write venjs.text({}) etc.
  div: (...args) => venjs.createElement('div', ...args),
  text: (...args) => venjs.createElement('text', ...args),
  button: (...args) => venjs.createElement('button', ...args),
  image: (...args) => venjs.createElement('image', ...args),
  input: (...args) => venjs.createElement('input', ...args),
  checkbox: (...args) => venjs.createElement('checkbox', ...args),
  icon: (...args) => venjs.createElement('icon', ...args),
  activityIndicator: (...args) => venjs.createElement('activityIndicator', ...args),
  a: (...args) => venjs.createElement('a', ...args),
  openExternalURL: (url) => {
    const target = typeof url === 'string' ? url.trim() : '';
    if (!target) return;

    if (window.Android && typeof window.Android.openExternalURL === 'function') {
      window.Android.openExternalURL(target);
      return;
    }

    if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.openExternalURL) {
      window.webkit.messageHandlers.openExternalURL.postMessage(target);
      return;
    }

    if (typeof window.open === 'function') {
      window.open(target, '_blank');
    }
  },

  api: {
    connect: async (request = {}) => {
      const options = typeof request === 'string' ? { url: request } : { ...(request || {}) };
      const url = typeof options.url === 'string' ? options.url.trim() : '';
      if (!url) {
        throw new Error('venjs.api.connect requires a url.');
      }

      const method = String(options.method || 'GET').toUpperCase();
      const headers = options.headers && typeof options.headers === 'object' ? { ...options.headers } : {};
      const params = options.params && typeof options.params === 'object' ? options.params : null;
      const timeoutMs = Number.isFinite(options.timeoutMs) ? Number(options.timeoutMs) : 15000;
      const responseType = String(options.responseType || 'json').toLowerCase();

      let finalUrl = url;
      if (params) {
        const searchParams = new URLSearchParams();
        Object.keys(params).forEach((key) => {
          const value = params[key];
          if (value === undefined || value === null) return;
          searchParams.append(key, String(value));
        });
        const qs = searchParams.toString();
        if (qs) {
          finalUrl += (finalUrl.includes('?') ? '&' : '?') + qs;
        }
      }

      const fetchOptions = {
        method,
        headers,
        credentials: options.credentials || 'omit'
      };

      const hasBody = options.body !== undefined && options.body !== null && method !== 'GET' && method !== 'HEAD';
      if (hasBody) {
        const body = options.body;
        const isFormData = typeof FormData !== 'undefined' && body instanceof FormData;
        const isPlainObject = body && typeof body === 'object' && !Array.isArray(body) && !isFormData;
        const hasContentType = Object.keys(headers).some((key) => key.toLowerCase() === 'content-type');

        if (isPlainObject) {
          fetchOptions.body = JSON.stringify(body);
          if (!hasContentType) {
            fetchOptions.headers['Content-Type'] = 'application/json';
          }
        } else {
          fetchOptions.body = body;
        }
      }

      let timeoutId = null;
      if (typeof AbortController !== 'undefined' && timeoutMs > 0) {
        const controller = new AbortController();
        fetchOptions.signal = controller.signal;
        timeoutId = setTimeout(() => controller.abort(), timeoutMs);
      }

      try {
        const response = await fetch(finalUrl, fetchOptions);
        const responseHeaders = {};
        response.headers.forEach((value, key) => {
          responseHeaders[key] = value;
        });

        let data = null;
        if (responseType === 'text') {
          data = await response.text();
        } else if (responseType === 'blob') {
          data = await response.blob();
        } else if (responseType === 'arraybuffer') {
          data = await response.arrayBuffer();
        } else {
          const rawText = await response.text();
          if (rawText) {
            try {
              data = JSON.parse(rawText);
            } catch (_) {
              data = rawText;
            }
          }
        }

        return {
          ok: response.ok,
          status: response.status,
          statusText: response.statusText,
          headers: responseHeaders,
          data
        };
      } catch (error) {
        return {
          ok: false,
          status: 0,
          statusText: error && error.name ? String(error.name) : 'NetworkError',
          headers: {},
          data: null,
          error: error && error.message ? String(error.message) : String(error)
        };
      } finally {
        if (timeoutId) {
          clearTimeout(timeoutId);
        }
      }
    }
  },

  // Simple reactive state holder for framework apps.
  state: (initialValue) => {
    let value = initialValue;
    return {
      get: () => value,
      set: (nextValue) => {
        value = typeof nextValue === 'function' ? nextValue(value) : nextValue;
        // Keep keyboard/focus stable while native input is typing.
        if (venjs._nativeEventContext !== 'change' && typeof venjs._rootComponent === 'function') {
          venjs.rerender();
        }
        return value;
      }
    };
  },

  createStore: (initialState = {}) => {
    let state = initialState;
    const listeners = new Set();

    const notify = () => {
      listeners.forEach((listener) => {
        try {
          listener(state);
        } catch (error) {
          console.error('venjsX store listener error:', error);
        }
      });
    };

    return {
      getState: () => state,
      setState: (next) => {
        const computed = typeof next === 'function' ? next(state) : next;
        if (computed && typeof computed === 'object' && !Array.isArray(computed) && state && typeof state === 'object' && !Array.isArray(state)) {
          state = { ...state, ...computed };
        } else {
          state = computed;
        }
        notify();
        if (typeof venjs._rootComponent === 'function') {
          venjs.rerender();
        }
        return state;
      },
      subscribe: (listener) => {
        if (typeof listener !== 'function') {
          return () => {};
        }
        listeners.add(listener);
        return () => listeners.delete(listener);
      }
    };
  },

  createRouter: (routes = {}, options = {}) => {
    const normalize = (path) => {
      if (!path) return '/';
      const raw = String(path).trim();
      if (!raw || raw === '#') return '/';
      return raw.startsWith('/') ? raw : `/${raw.replace(/^#/, '')}`;
    };

    const current = venjs.state(normalize(options.initialRoute || '/'));
    const historyStack = [current.get()];
    const navigate = (to) => {
      const next = normalize(to);
      const active = current.get();
      if (next === active) return active;
      historyStack.push(next);
      return current.set(next);
    };
    const replace = (to) => {
      const next = normalize(to);
      if (historyStack.length === 0) {
        historyStack.push(next);
      } else {
        historyStack[historyStack.length - 1] = next;
      }
      return current.set(next);
    };
    const canGoBack = () => historyStack.length > 1;
    const back = () => {
      if (!canGoBack()) return false;
      historyStack.pop();
      current.set(historyStack[historyStack.length - 1]);
      return true;
    };

    const resolve = () => {
      const path = current.get();
      const page = routes[path] || routes['*'] || options.notFound;
      if (!page) {
        return venjs.text({}, `Route "${path}" not found`);
      }
      return typeof page === 'function' ? page({ path, navigate }) : page;
    };

    const api = {
      path: current,
      navigate,
      push: navigate,
      replace,
      back,
      canGoBack,
      view: () => resolve(),
      resolve
    };

    if (typeof window !== 'undefined') {
      window.__venjsHandleNativeBack = () => back();
    }

    return api;
  },

  setErrorHandler: (handler) => {
    venjs._errorHandler = typeof handler === 'function' ? handler : null;
  },

  reloadStyles: () => {
    venjs._styleRegistryReady = false;
    venjs._collectDocumentStyleRules();
    if (typeof venjs._rootComponent === 'function') {
      venjs.rerender();
    }
  },

  _installStyleReloadWatch: () => {
    if (venjs._styleReloadWatchInstalled || typeof window === 'undefined') {
      return;
    }
    venjs._styleReloadWatchInstalled = true;
    const refresh = () => venjs.reloadStyles();
    if (typeof document !== 'undefined' && document.readyState === 'complete') {
      window.setTimeout(refresh, 0);
      return;
    }
    window.addEventListener('load', refresh, { once: true });
    window.setTimeout(refresh, 120);
  },

  rerender: () => {
    venjs._renderRoot();
  },

  /**
   * Supports both signatures:
   * - venjs.mount(App)
   * - venjs.mount(null, App)
   */
  mount: (arg1, arg2, arg3) => {
    const component = typeof arg1 === 'function' ? arg1 : arg2;
    const options = typeof arg1 === 'function' ? arg2 : arg3;
    if (typeof component !== 'function') {
      throw new Error('venjsX.mount requires a component function.');
    }
    if (options && typeof options.onError === 'function') {
      venjs.setErrorHandler(options.onError);
    }
    venjs._collectDocumentStyleRules();
    venjs._installStyleReloadWatch();
    venjs._rootComponent = component;
    venjs._renderRoot();
  }
};

if (typeof module !== 'undefined' && module.exports) {
  module.exports = venjs;
}

if (typeof window !== 'undefined') {
  window.venjs = venjs;
  window.venjsX = venjs;
  // Backward compatibility for legacy apps.
  window.venX = venjs;
  window.__venjsDispatchNativeEvent = (eventId, payload) => {
    venjs._dispatchEvent(Number(eventId), payload || {});
  };
}
