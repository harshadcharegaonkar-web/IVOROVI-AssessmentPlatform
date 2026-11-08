import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";
import { examHeaders } from "./security-headers";

// Separate application, origin and bundle (C-4): nothing from the staff app is imported. Dev server on 5174 proxies the
// delivery API (DELIVERY process, port 8090). `vite preview` serves the production build with the production headers.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: { "/api/v1/delivery": { target: process.env.DELIVERY_PROXY_TARGET ?? "http://localhost:8090", changeOrigin: false } },
  },
  preview: {
    port: 4174,
    strictPort: true,
    headers: examHeaders(process.env.OIDC_AUTHORITY ?? "http://localhost:8180/realms/ivorovi-candidates"),
    proxy: { "/api/v1/delivery": { target: process.env.DELIVERY_PROXY_TARGET ?? "http://localhost:8090", changeOrigin: false } },
  },
  build: {
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (!id.includes("node_modules")) return undefined;
          if (/[\\/](react|react-dom|scheduler)[\\/]/.test(id)) return "vendor-react";
          return undefined;
        },
      },
    },
  },
  test: { environment: "jsdom", globals: true, setupFiles: ["./src/test-setup.ts"], include: ["src/**/*.test.{ts,tsx}"] },
});
