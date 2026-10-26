import { readFileSync } from 'node:fs';
const css = readFileSync('src/components/styles/tokens.css', 'utf8');
import { describe, expect, it } from 'vitest';

const blocks = {
  light: css.match(/:root, \[data-theme="light"\] \{([\s\S]*?)\}/)[1],
  dark: css.match(/\[data-theme="dark"\] \{([\s\S]*?)\}/)[1],
};
const palette = (block) => Object.fromEntries([...block.matchAll(/(--wp-color-[\w-]+):\s*(#[\da-f]+);/gi)].map((match) => [match[1], match[2]]));
const luminance = (hex) => {
  const channels = hex.slice(1).match(/../g).map((value) => parseInt(value, 16) / 255).map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
};
const contrast = (a, b) => (Math.max(luminance(a), luminance(b)) + 0.05) / (Math.min(luminance(a), luminance(b)) + 0.05);

describe.each(Object.entries(blocks))('%s Route-line text contrast', (theme, block) => {
  const colors = palette(block);
  it.each([
    ['text', 'bg'], ['text', 'surface'], ['text-muted', 'bg'], ['text-muted', 'surface'],
    ['primary', 'surface'], ['primary-contrast', 'primary'],
    ['error', 'error-bg'], ['warning', 'warning-bg'], ['success', 'success-bg'],
  ])('keeps %s on %s above 4.5:1', (foreground, background) => {
    expect(contrast(colors[`--wp-color-${foreground}`], colors[`--wp-color-${background}`]), `${theme}: ${foreground}/${background}`).toBeGreaterThanOrEqual(4.5);
  });
});
