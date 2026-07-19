import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const [boxWebPath, boxWasmPath, avcWebPath, avcWasmPath, hostPath, fixturePath] =
  process.argv.slice(2);
if (!boxWebPath || !boxWasmPath || !avcWebPath || !avcWasmPath || !hostPath || !fixturePath)
  throw new Error("missing conformance paths");

const bytes = fs.readFileSync(path.resolve(fixturePath));
if (bytes.length !== 12171) throw new Error("unexpected fixture length");
const host = await import(pathToFileURL(path.resolve(hostPath)));

const boxWeb = await import(pathToFileURL(path.resolve(boxWebPath)));
if (boxWeb.kotobaArtifact.requiredCapabilities.length !== 0)
  throw new Error("ISO-BMFF Web graph requested a capability");
if (boxWeb.instantiateKotoba().main() !== 42n)
  throw new Error("ISO-BMFF Web negative result mismatch");
if (boxWeb.instantiateKotoba()["fixture-check"](Array.from(bytes, BigInt)) !== 42n)
  throw new Error("ISO-BMFF Web fixture mismatch");

const boxWasmBytes = fs.readFileSync(path.resolve(boxWasmPath));
const boxWasmNegative = await host.instantiateKotoba(boxWasmBytes);
if (boxWasmNegative.instance.exports.main() !== 42n)
  throw new Error("ISO-BMFF Wasm negative result mismatch");
const boxWasm = await host.instantiateKotoba(boxWasmBytes);
if (boxWasm.instance.exports["fixture-check"](boxWasm.typedValues.bytes(bytes)) !== 42n)
  throw new Error("ISO-BMFF Wasm fixture mismatch");
let oversizedRejected = false;
try { boxWasm.typedValues.bytes(Buffer.alloc(16385)); }
catch { oversizedRejected = true; }
if (!oversizedRejected) throw new Error("oversized Wasm byte input was accepted");

const avcWeb = await import(pathToFileURL(path.resolve(avcWebPath)));
if (avcWeb.kotobaArtifact.requiredCapabilities.length !== 0)
  throw new Error("ISO-BMFF to H264 Web graph requested a capability");
if (avcWeb.instantiateKotoba().main() !== 42n)
  throw new Error("ISO-BMFF to H264 Web negative result mismatch");
if (avcWeb.instantiateKotoba()["fixture-check"](Array.from(bytes, BigInt)) !== 42n)
  throw new Error("ISO-BMFF to H264 Web fixture mismatch");

const avcWasmBytes = fs.readFileSync(path.resolve(avcWasmPath));
const avcWasmNegative = await host.instantiateKotoba(avcWasmBytes);
if (avcWasmNegative.instance.exports.main() !== 42n)
  throw new Error("ISO-BMFF to H264 Wasm negative result mismatch");
const avcWasm = await host.instantiateKotoba(avcWasmBytes);
if (avcWasm.instance.exports["fixture-check"](avcWasm.typedValues.bytes(bytes)) !== 42n)
  throw new Error("ISO-BMFF to H264 Wasm fixture mismatch");

const avcTypeOffset = bytes.indexOf(Buffer.from("avcC"));
if (avcTypeOffset < 4) throw new Error("avcC fixture box missing");
const payloadOffset = avcTypeOffset + 4;
const mutations = [
  ["unsupported avcC version", payloadOffset, 2],
  ["unsupported SPS count", payloadOffset + 5, 0],
  ["oversized SPS", payloadOffset + 6, 1],
  ["invalid SPS length byte", payloadOffset + 7, 256],
  ["out-of-range avcC box", avcTypeOffset - 4, 127],
];
for (const [label, offset, value] of mutations) {
  const webCandidate = Array.from(bytes, BigInt);
  webCandidate[offset] = BigInt(value);
  if (avcWeb.instantiateKotoba()["reject-check"](webCandidate) !== 42n)
    throw new Error(`Web accepted ${label}`);
  const instance = await host.instantiateKotoba(avcWasmBytes);
  if (value > 255) {
    let sealedHostRejected = false;
    try { instance.typedValues.bytes(webCandidate); }
    catch { sealedHostRejected = true; }
    if (!sealedHostRejected) throw new Error(`Wasm host accepted ${label}`);
  } else {
    const wasmCandidate = Buffer.from(bytes);
    wasmCandidate[offset] = value;
    if (instance.instance.exports["reject-check"](instance.typedValues.bytes(wasmCandidate)) !== 42n)
      throw new Error(`Wasm accepted ${label}`);
  }
}

console.log("isobmff-kotoba: bounded boxes and cross-repo avcC/SPS Web/Wasm conformance passed");
