/* eslint-disable no-console */

const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const path = require('path');
const { projectPaths } = require('./paths');

function contentTypeByExt(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  if (ext === '.html') return 'text/html; charset=utf-8';
  if (ext === '.js') return 'application/javascript; charset=utf-8';
  if (ext === '.css') return 'text/css; charset=utf-8';
  if (ext === '.json') return 'application/json; charset=utf-8';
  if (ext === '.map') return 'application/json; charset=utf-8';
  return 'application/octet-stream';
}

function safeRead(filePath) {
  try {
    return fs.readFileSync(filePath);
  } catch (_err) {
    return null;
  }
}

function createWebSocketAccept(key) {
  return crypto
    .createHash('sha1')
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`, 'binary')
    .digest('base64');
}

function wsFrameText(text) {
  const payload = Buffer.from(String(text), 'utf8');
  const len = payload.length;
  if (len < 126) {
    return Buffer.concat([Buffer.from([0x81, len]), payload]);
  }
  if (len < 65536) {
    const header = Buffer.from([0x81, 126, (len >> 8) & 0xff, len & 0xff]);
    return Buffer.concat([header, payload]);
  }
  throw new Error('WS payload too large');
}

async function startDevServer({ host, port, onRebuild }) {
  const clients = new Set();

  const server = http.createServer((req, res) => {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    const pathname = url.pathname;

    if (pathname === '/__venjsx_ws') {
      res.writeHead(426);
      res.end('Upgrade Required');
      return;
    }

    if (pathname === '/' || pathname === '/index.html') {
      const file = path.join(projectPaths.devDir, 'index.html');
      const body = safeRead(file);
      if (!body) {
        res.writeHead(500);
        res.end('Missing dev/index.html');
        return;
      }
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });
      res.end(body);
      return;
    }

    if (pathname === '/venjsX.js') {
      const file = path.join(projectPaths.root, 'js', 'venjsX.js');
      const body = safeRead(file);
      if (!body) {
        res.writeHead(404);
        res.end('Not found');
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/javascript; charset=utf-8', 'Cache-Control': 'no-cache' });
      res.end(body);
      return;
    }

    if (pathname === '/bundle.js') {
      const file = path.join(projectPaths.root, 'dist', 'bundle.js');
      const body = safeRead(file);
      if (!body) {
        res.writeHead(404);
        res.end('Bundle not built yet');
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/javascript; charset=utf-8', 'Cache-Control': 'no-cache' });
      res.end(body);
      return;
    }

    res.writeHead(404);
    res.end('Not found');
  });

  server.on('upgrade', (req, socket) => {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    if (url.pathname !== '/__venjsx_ws') {
      socket.destroy();
      return;
    }

    const key = req.headers['sec-websocket-key'];
    if (!key) {
      socket.destroy();
      return;
    }

    const accept = createWebSocketAccept(String(key));
    socket.write(
      [
        'HTTP/1.1 101 Switching Protocols',
        'Upgrade: websocket',
        'Connection: Upgrade',
        `Sec-WebSocket-Accept: ${accept}`,
        '',
        ''
      ].join('\r\n')
    );

    clients.add(socket);
    socket.on('close', () => clients.delete(socket));
    socket.on('error', () => clients.delete(socket));
  });

  server.listen(port, host);
  console.log(`venjsx dev server running at http://${host}:${port}/`);

  const watchedFiles = [
    path.join(projectPaths.root, 'src'),
    path.join(projectPaths.root, 'js', 'venjsX.js')
  ];

  const watchers = [];
  let rebuildTimer = null;

  function broadcastReload() {
    const frame = wsFrameText('reload');
    clients.forEach((sock) => {
      try {
        sock.write(frame);
      } catch (_err) {
      }
    });
  }

  function scheduleRebuild() {
    if (rebuildTimer) clearTimeout(rebuildTimer);
    rebuildTimer = setTimeout(async () => {
      try {
        await onRebuild();
        broadcastReload();
        console.log('venjsx: rebuilt + reloaded');
      } catch (err) {
        console.error('venjsx: rebuild failed', err && err.stack ? err.stack : String(err));
      }
    }, 120);
  }

  watchedFiles.forEach((p) => {
    if (!fs.existsSync(p)) return;
    try {
      const w = fs.watch(p, { recursive: true }, () => scheduleRebuild());
      watchers.push(w);
    } catch (_err) {
    }
  });

  const stop = () => {
    watchers.forEach((w) => {
      try { w.close(); } catch (_err) {}
    });
    clients.forEach((sock) => {
      try { sock.destroy(); } catch (_err) {}
    });
    try { server.close(); } catch (_err) {}
  };

  process.on('SIGINT', () => {
    console.log('\nvenjsx: shutting down');
    stop();
    process.exit(0);
  });
}

module.exports = { startDevServer };

