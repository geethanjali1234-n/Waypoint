import fs from 'fs';
import path from 'path';

/** Ensures Gradle can find an installed Android SDK on a developer machine. */
export function configureAndroidSdk(androidDir) {
  const localPropertiesPath = path.join(androidDir, 'local.properties');
  if (fs.existsSync(localPropertiesPath)) return;

  const candidates = [
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk'),
  ].filter(Boolean);
  const sdkDir = candidates.find((candidate) => fs.existsSync(candidate));

  if (!sdkDir) {
    throw new Error('Android SDK was not found. Set ANDROID_HOME or install it through Android Studio.');
  }

  fs.writeFileSync(localPropertiesPath, `sdk.dir=${sdkDir.replace(/\\/g, '\\\\')}\n`);
  console.log(`Configured Android SDK from ${sdkDir}`);
}
