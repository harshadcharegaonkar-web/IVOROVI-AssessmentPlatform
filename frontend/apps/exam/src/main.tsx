import "@fontsource-variable/inter";
import "./styles.css";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { createUserManager } from "./auth";
import { loadConfig } from "./config";

const root = createRoot(document.getElementById("root")!);
loadConfig()
  .then((config) => root.render(<App manager={createUserManager(config)} />))
  .catch((e: unknown) =>
    root.render(
      <main className="mx-auto max-w-lg p-6">
        <h1 className="text-xl font-semibold">The exam portal could not start</h1>
        <p className="mt-2 text-muted-strong">{e instanceof Error ? e.message : String(e)}</p>
      </main>,
    ),
  );
