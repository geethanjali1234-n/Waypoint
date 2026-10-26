import os from 'node:os';

const IPV4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;

function validIpv4(value) {
  const match = IPV4.exec(value || '');
  return Boolean(match) && match.slice(1).every((part) => Number(part) >= 0 && Number(part) <= 255);
}

function privateIpv4(value) {
  if (!validIpv4(value)) return false;
  const [first, second] = value.split('.').map(Number);
  return first === 10 || (first === 172 && second >= 16 && second <= 31) || (first === 192 && second === 168);
}

/** Returns an explicit IPv4 override or the active private LAN address. */
export function resolveLanAddress(override) {
  if (override) {
    if (!validIpv4(override) || override.startsWith('127.') || override.startsWith('169.254.')) {
      throw new Error(`Invalid LAN IPv4 address: ${override}`);
    }
    return override;
  }

  const addresses = Object.values(os.networkInterfaces())
    .flat()
    .filter((network) => network && network.family === 'IPv4' && !network.internal)
    .map((network) => network.address);
  const address = addresses.find(privateIpv4);
  if (!address) throw new Error('No active private IPv4 address was found. Connect to Wi-Fi or pass an IP address explicitly.');
  return address;
}

export function lanUrl(port, override) {
  return `http://${resolveLanAddress(override)}:${port}`;
}
