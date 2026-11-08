/**
 * Device key (C-4): a non-extractable ECDSA P-256 key pair generated in the browser and kept in this origin's IndexedDB.
 * The private key can sign but never be exported or read by script. Attempt tokens are bound to it from 2.5; 2.0 only
 * creates it and reports its fingerprint in the system check.
 */
const DB = "ivorovi-exam";
const STORE = "keys";
const ID = "device";

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open(DB, 1);
    r.onupgradeneeded = () => r.result.createObjectStore(STORE);
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

function tx<T>(db: IDBDatabase, mode: IDBTransactionMode, run: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const req = run(db.transaction(STORE, mode).objectStore(STORE));
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function deviceKey(): Promise<{ fingerprint: string; extractable: boolean }> {
  const db = await open();
  let pair = (await tx(db, "readonly", (s) => s.get(ID))) as CryptoKeyPair | undefined;
  if (!pair) {
    pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, false, ["sign", "verify"]);
    await tx(db, "readwrite", (s) => s.put(pair, ID));
  }
  const spki = await crypto.subtle.exportKey("spki", pair.publicKey);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", spki));
  const fingerprint = Array.from(digest.slice(0, 8), (b) => b.toString(16).padStart(2, "0")).join(":");
  return { fingerprint, extractable: pair.privateKey.extractable };
}
