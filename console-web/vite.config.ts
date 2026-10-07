import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // 浏览器只跟 5173 打交道，/api 由 Vite 转给 8080：
    // 这样刷新接口的 Origin 校验（auth.oauth.console-base-url）天然通过。
    proxy: {
      '/api': { target: 'http://localhost:8080' },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
