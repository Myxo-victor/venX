/* eslint-disable no-console */

const fs = require('fs');
const path = require('path');
const { projectPaths } = require('./paths');

function copyFileSafe(src, dest) {
  if (!fs.existsSync(src)) {
    throw new Error(`Bundle not found: ${src}`);
  }
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.copyFileSync(src, dest);
}

function syncBundleToPlatforms(bundlePath) {
  copyFileSafe(bundlePath, projectPaths.androidMainJs);
  copyFileSafe(bundlePath, projectPaths.iosMainJs);
  console.log('venjsx: synced bundle to Android + iOS entrypoints');
  console.log(`- ${projectPaths.androidMainJs}`);
  console.log(`- ${projectPaths.iosMainJs}`);
}

function syncRuntimeToPlatforms() {
  copyFileSafe(projectPaths.canonicalRuntimeJs, projectPaths.androidRuntimeJs);
  copyFileSafe(projectPaths.canonicalRuntimeJs, projectPaths.iosRuntimeJs);
  console.log('venjsx: synced runtime to Android + iOS shells');
  console.log(`- ${projectPaths.androidRuntimeJs}`);
  console.log(`- ${projectPaths.iosRuntimeJs}`);
}

module.exports = { syncBundleToPlatforms, syncRuntimeToPlatforms };
