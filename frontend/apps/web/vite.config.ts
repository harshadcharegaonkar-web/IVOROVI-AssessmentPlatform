import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { "/api": { target: process.env.API_PROXY_TARGET ?? "http://localhost:8080", changeOrigin: false } },
  },
  build: {
    sourcemap: true,
    rollupOptions: {
      output: {
        // Stable core vendor chunks (long-term cacheable); other libraries follow the routes that use them.
        manualChunks(id: string) {
          if (!id.includes("node_modules")) return undefined;
          if (/[\\/](react|react-dom|scheduler)[\\/]/.test(id)) return "vendor-react";
          if (/[\\/](react-router|@tanstack|oidc-client-ts|react-oidc-context|openapi-fetch)[\\/]/.test(id)) return "vendor-app";
          // Everything else is split automatically, so page-only libraries (forms, validation) stay lazy.
          return undefined;
        },
      },
    },
  },
  test: { environment: "jsdom", globals: true, setupFiles: ["./src/test-setup.ts"], include: ["src/**/*.test.{ts,tsx}"] },
});
