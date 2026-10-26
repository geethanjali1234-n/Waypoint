import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Plugin to ensure Android APK files are served with the proper MIME type
// so mobile browsers don't automatically rename .apk to .zip
function apkMimePlugin() {
  return {
    name: 'apk-mime-plugin',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url && req.url.includes('.apk')) {
          res.setHeader('Content-Type', 'application/vnd.android.package-archive');
          res.setHeader('Content-Disposition', 'attachment; filename="waypoint.apk"');
        }
        next();
      });
    },
    configurePreviewServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url && req.url.includes('.apk')) {
          res.setHeader('Content-Type', 'application/vnd.android.package-archive');
          res.setHeader('Content-Disposition', 'attachment; filename="waypoint.apk"');
        }
        next();
      });
    },
  };
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), apkMimePlugin()],
  build: {
    cssMinify: false,
    chunkSizeWarningLimit: 2000,
    rollupOptions: {
      onwarn(warning, warn) {
        if (warning.code === 'INEFFECTIVE_DYNAMIC_IMPORT') return;
        warn(warning);
      },
    },
  },
  server: {
    // Keep the development URL stable so the frontend does not move between
    // 5173 and 5174 when another Vite process is already running.
    port: 5173,
    host: true,
    strictPort: true,
    // WSL does not reliably receive filesystem events for edits made by
    // Windows tools under /mnt/c. Poll so Vite invalidates cached modules.
    watch: {
      usePolling: true,
      interval: 300,
      ignored: ['**/node_modules*/**', '**/dist/**'],
      awaitWriteFinish: {
        stabilityThreshold: 600,
        pollInterval: 100,
      },
    },
    proxy: {
      '/api': {
        // Use IPv4 explicitly to avoid Windows localhost IPv4/IPv6 retries.
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            // Forward localhost origin so backend accepts mobile device requests
            proxyReq.setHeader('Origin', 'http://127.0.0.1:5173');
          });
          proxy.on('proxyRes', (proxyRes, req) => {
            if (req.headers.origin) {
              proxyRes.headers['access-control-allow-origin'] = req.headers.origin;
              proxyRes.headers['access-control-allow-credentials'] = 'true';
            }
          });
        },
      },
      '/uploads': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setupTests.js',
    include: ['src/test/**/*.test.{js,jsx}'],
    testTimeout: 30000,
  },
})
