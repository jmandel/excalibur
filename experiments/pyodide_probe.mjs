import { loadPyodide } from 'pyodide';

const pyodide = await loadPyodide();
console.log('pyodide', pyodide.version);
console.log(await pyodide.runPythonAsync(`
import sys, platform
print(sys.version)
print(platform.platform())
`));
const pkgs = ['lxml', 'css-parser', 'Pillow', 'python-dateutil', 'regex', 'chardet', 'beautifulsoup4', 'html5lib', 'webencodings', 'msgpack', 'html5-parser', 'tzdata'];
for (const p of pkgs) {
  try {
    await pyodide.loadPackage(p);
    console.log('PKG OK', p);
  } catch (e) {
    console.log('PKG MISS', p, String(e).split('\n')[0]);
  }
}
