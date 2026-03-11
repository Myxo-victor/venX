#!/usr/bin/env node
/* eslint-disable no-console */

const path = require('path');
const { buildBundle, ensureDistDir } = require('./lib/bundler');
const { startDevServer } = require('./lib/devServer');
const { syncBundleToPlatforms, syncRuntimeToPlatforms } = require('./lib/sync');
const { projectPaths } = require('./lib/paths');

function usage() {
  console.log(`
venjsx - venjsX tooling

Usage:
  venjsx dev [--port 5173] [--host 0.0.0.0]
  venjsx build [--entry src/app.js] [--out dist/bundle.js] [--sync]
  venjsx sync [--bundle dist/bundle.js]

Notes:
  - For best results, install esbuild: npm i
  - Android emulator reaches your host at http://10.0.2.2:<port>/
  - iOS simulator can reach your host at http://localhost:<port>/
`.trim());
}

function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const item = argv[i];
    if (item.startsWith('--')) {
      const key = item.slice(2);
      const next = argv[i + 1];
      if (next && !next.startsWith('--')) {
        args[key] = next;
        i += 1;
      } else {
        args[key] = true;
      }
    } else {
      args._.push(item);
    }
  }
  return args;
}

async function main() {
  const argv = process.argv.slice(2);
  const args = parseArgs(argv);
  const cmd = args._[0];

  if (!cmd || cmd === 'help' || cmd === '--help' || cmd === '-h') {
    usage();
    process.exit(0);
  }

  if (cmd === 'build') {
    const entry = path.resolve(projectPaths.root, args.entry || projectPaths.defaultEntry);
    const outFile = path.resolve(projectPaths.root, args.out || projectPaths.defaultBundleOut);
    ensureDistDir(path.dirname(outFile));
    await buildBundle({ entry, outFile, sourcemap: true, minify: false });
    syncRuntimeToPlatforms();
    if (args.sync) {
      syncBundleToPlatforms(outFile);
    }
    return;
  }

  if (cmd === 'sync') {
    const bundle = path.resolve(projectPaths.root, args.bundle || projectPaths.defaultBundleOut);
    syncRuntimeToPlatforms();
    syncBundleToPlatforms(bundle);
    return;
  }

  if (cmd === 'dev') {
    const host = String(args.host || '0.0.0.0');
    const port = Number(args.port || 5173);
    const entry = path.resolve(projectPaths.root, args.entry || projectPaths.defaultEntry);
    const outFile = path.resolve(projectPaths.root, projectPaths.defaultBundleOut);

    ensureDistDir(path.dirname(outFile));
    await buildBundle({ entry, outFile, sourcemap: 'inline', minify: false });
    syncRuntimeToPlatforms();
    syncBundleToPlatforms(outFile);

    await startDevServer({
      host,
      port,
      onRebuild: async () => {
        await buildBundle({ entry, outFile, sourcemap: 'inline', minify: false });
        syncRuntimeToPlatforms();
        syncBundleToPlatforms(outFile);
      }
    });
    return;
  }

  console.error(`Unknown command: ${cmd}`);
  usage();
  process.exit(1);
}

main().catch((err) => {
  console.error(err && err.stack ? err.stack : String(err));
  process.exit(1);
});
