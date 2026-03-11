const path = require('path');

const root = path.resolve(__dirname, '..', '..');

const projectPaths = {
  root,
  srcDir: path.join(root, 'src'),
  distDir: path.join(root, 'dist'),
  devDir: path.join(root, 'dev'),
  defaultEntry: path.join('src', 'app.js'),
  defaultBundleOut: path.join('dist', 'bundle.js'),
  canonicalRuntimeJs: path.join(root, 'js', 'venjsX.js'),
  androidRuntimeJs: path.join(root, 'android', 'app', 'src', 'main', 'assets', 'js', 'venjsX.js'),
  androidMainJs: path.join(root, 'android', 'app', 'src', 'main', 'assets', 'app', 'pages', 'main.js'),
  iosRuntimeJs: path.join(root, 'ios', 'app', 'js', 'venjsX.js'),
  iosMainJs: path.join(root, 'ios', 'app', 'main.js')
};

module.exports = { projectPaths };
