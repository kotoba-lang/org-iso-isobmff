import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const [webPath, wasmPath, hostPath, fixturePath] = process.argv.slice(2);
if (!webPath || !wasmPath || !hostPath || !fixturePath)
  throw new Error("missing conformance paths");
const bytes = fs.readFileSync(path.resolve(fixturePath));
if (bytes.length !== 12171) throw new Error("unexpected fixture length");
const web = await import(pathToFileURL(path.resolve(webPath)));
if (web.kotobaArtifact.requiredCapabilities.length !== 0)
  throw new Error("ISO-BMFF Web graph requested a capability");
if (web.instantiateKotoba().main() !== 42n)
  throw new Error("ISO-BMFF Web negative result mismatch");
const webFixture = web.instantiateKotoba();
if (webFixture["fixture-check"](Array.from(bytes, BigInt)) !== 42n)
  throw new Error("ISO-BMFF Web fixture mismatch");
const host = await import(pathToFileURL(path.resolve(hostPath)));
const wasmBytes = fs.readFileSync(path.resolve(wasmPath));
const wasmNegative = await host.instantiateKotoba(wasmBytes);
if (wasmNegative.instance.exports.main() !== 42n)
  throw new Error("ISO-BMFF Wasm negative result mismatch");
const wasm = await host.instantiateKotoba(wasmBytes);
const typedBytes = wasm.typedValues.bytes(bytes);
if (wasm.instance.exports["fixture-check"](typedBytes) !== 42n)
  throw new Error("ISO-BMFF Wasm fixture mismatch");
let oversizedRejected = false;
try { wasm.typedValues.bytes(Buffer.alloc(16385)); }
catch { oversizedRejected = true; }
if (!oversizedRejected) throw new Error("oversized Wasm byte input was accepted");
console.log("isobmff-kotoba: real-fixture Web/Wasm conformance passed");
