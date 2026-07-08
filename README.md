# kotoba-lang/org-iso-isobmff

Zero-dep portable `.cljc` ISO Base Media File Format reader/writer
(ISO/IEC 14496-12) — the box-tree container behind MP4/MOV video and
AVIF/HEIC still images. Named `org-iso-isobmff` (ISO/IEC-numbered spec,
consistent with `org-iso-jpeg`/`org-iso-pdf`/`org-iso-opentype`/`org-iso-h264`
in the same batch).

**This repo unifies two previously independent, duplicate box-tree
implementations**: `kasane.isobmff` (a flat walker used to read AVIF/HEIC
`ftyp`/`ispe` metadata, from `kotoba-lang/kasane`, ADR-2606280010) and
`utsushi.container`/`demux`/`mux`/`remux` (a nested-tree walker + full MP4
demux/mux/remux pipeline, from `kotoba-lang/utsushi`, ADR-2606272200). Both
were parsing the *same spec*; merging them into one `isobmff.box` walker
also fixed a real gap — the MP4-side walker didn't know `meta` is a
FullBox (4-byte version+flags before its children), which the AVIF-side
walker handled correctly. The merge carries that fix into the shared
engine, and both consumers (`isobmff.meta` for AVIF/HEIC, `isobmff.demux`/
`mux`/`remux` for MP4) now sit on one correct implementation.

## Namespaces

| ns | role | source |
|---|---|---|
| `isobmff.bytes` | big-endian read/write primitives | utsushi.bytes |
| `isobmff.box` | generic recursive box-tree walker (shared engine) | merged kasane.isobmff + utsushi.container |
| `isobmff.meta` | AVIF/HEIC brand + dimensions (ftyp/ispe) | kasane.isobmff |
| `isobmff.blob` | content-id placeholder for sample/packet references | utsushi.blob |
| `isobmff.demux` | MP4 → per-track samples via the stbl sample table | utsushi.demux |
| `isobmff.mux` | per-track samples → MP4 (re-encode-free) | utsushi.mux |
| `isobmff.remux` | trim / concat (re-encode-free edits) | utsushi.remux |

Pixel/frame decode (AV1/HEVC/H.264/...) is out of scope — this repo only
reads/writes the container structure; coded media stays an opaque blob for
the caller (or a capability-gated native codec, per `kotoba-lang/utsushi`'s
own design).

## Usage

```clojure
(require '[isobmff.meta :as meta] '[isobmff.demux :as demux]
         '[isobmff.mux :as mux] '[isobmff.remux :as remux])

(meta/parse avif-bytes)                     ; => {:brand :width :height :boxes}
(def d (demux/demux mp4-bytes))             ; => {:timescale :tracks [...]}
(remux/remux (remux/trim d 0 5000))         ; re-encode-free trim → MP4 bytes
```

## Test

```sh
clojure -M:test
```
