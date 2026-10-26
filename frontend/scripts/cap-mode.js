import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import { lanUrl } from './lan-address.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const rootDir = path.resolve(__dirname, '..');
const configPath = path.join(rootDir, 'capacitor.config.json');

const mode = process.argv[2] || 'status';

if (!fs.existsSync(configPath)) {
  console.error(`Config file not found at ${configPath}`);
  process.exit(1);
}

const config = JSON.parse(fs.readFileSync(configPath, 'utf-8'));
config.server = config.server || {};

if (mode === 'live') {
  const liveUrl = lanUrl(5173, process.argv[3]);
  config.server.url = liveUrl;
  config.server.cleartext = true;
  config.server.androidScheme = 'http';
  fs.writeFileSync(configPath, JSON.stringify(config, null, 2) + '\n');
  console.log(`\n🚀 Capacitor configured for LIVE RELOAD -> ${liveUrl}`);
  console.log('Syncing configuration to Android project...');
  execSync('npx cap copy android', { cwd: rootDir, stdio: 'inherit' });
  console.log('\n✅ Done! Any JSX/CSS edits in your IDE will now update instantly on your phone without installing!\n');
} else if (mode === 'standalone') {
  delete config.server.url;
  config.server.cleartext = true;
  config.server.androidScheme = 'http';
  fs.writeFileSync(configPath, JSON.stringify(config, null, 2) + '\n');
  console.log('\n📦 Capacitor configured for STANDALONE mode (bundled offline assets).');
  console.log('Syncing configuration to Android project...');
  execSync('npx cap copy android', { cwd: rootDir, stdio: 'inherit' });
  console.log('\n✅ Done! Standalone mode active.\n');
} else {
  console.log(`Current Capacitor Server Config:`, config.server);
  console.log(`Usage: node scripts/cap-mode.js [live|standalone] [optional-ip]`);
}
