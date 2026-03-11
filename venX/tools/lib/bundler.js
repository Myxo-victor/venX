/* eslint-disable no-console */

const fs = require('fs');
const path = require('path');

function ensureDistDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function tryRequireEsbuild() {
  try {
    // eslint-disable-next-line global-require, import/no-extraneous-dependencies
    return require('esbuild');
  } catch (_err) {
    return null;
  }
}

async function buildWithEsbuild(esbuild, { entry, outFile, sourcemap, minify }) {
  await esbuild.build({
    entryPoints: [entry],
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: ['es2017'],
    sourcemap,
    minify: Boolean(minify),
    outfile: outFile,
    define: {
      'process.env.NODE_ENV': JSON.stringify(minify ? 'production' : 'development')
    }
  });
}

async function buildBundle({ entry, outFile, sourcemap = true, minify = false }) {
  const esbuild = tryRequireEsbuild();
  if (esbuild) {
    await buildWithEsbuild(esbuild, { entry, outFile, sourcemap, minify });
    return;
  }

  console.warn('venjsx: esbuild not installed; falling back to copying entry file (no npm deps, no TS, no tree-shaking).');
  const code = fs.readFileSync(entry, 'utf8');
  ensureDistDir(path.dirname(outFile));
  fs.writeFileSync(outFile, code, 'utf8');
}

module.exports = { buildBundle, ensureDistDir };

