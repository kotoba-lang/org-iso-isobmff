(ns isobmff.mux
  "Minimal ISO BMFF (MP4) writer, for re-encode-free remux output. Lays out
   `ftyp + mdat + moov` (mdat-first) so chunk offsets compute in one pass
   (no two-pass patching). Normalizes to 1 track = 1 chunk (all samples laid
   out contiguously). stsd is re-emitted verbatim from whatever
   isobmff.demux captured (codec config stays opaque passthrough).
   R0: 32-bit box sizes, no edit list, width/height left 0 (structurally
   valid, dimensions unset).

   Extracted from kotoba-lang/utsushi (utsushi.mux, ADR-2606272200) as part
   of `org-iso-isobmff`."
  (:require [isobmff.bytes :as b]))

(defn- box [type payload]
  (let [p (vec payload), size (+ 8 (count p))]
    (into (into (b/wu32 size) (b/wstr type)) p)))

(defn- fbox [type version flags payload]
  (box type (concat (b/wu8 version) (b/wu24 flags) payload)))

(def ^:private identity-matrix
  (mapcat b/wu32 [0x00010000 0 0 0 0x00010000 0 0 0 0x40000000]))

(def ^:private vmhd (fbox "vmhd" 0 1 (concat (b/wu16 0) (b/wu16 0) (b/wu16 0) (b/wu16 0))))
(def ^:private smhd (fbox "smhd" 0 0 (concat (b/wu16 0) (b/wu16 0))))
(def ^:private dref (fbox "dref" 0 0 (concat (b/wu32 1) (fbox "url " 0 1 []))))
(def ^:private dinf (box "dinf" dref))

(defn- mvhd [timescale duration next-track]
  (fbox "mvhd" 0 0
        (concat (b/wu32 0) (b/wu32 0) (b/wu32 timescale) (b/wu32 duration)
                (b/wu32 0x00010000) (b/wu16 0x0100) (b/wu16 0) (b/wu32 0) (b/wu32 0)
                identity-matrix (repeat 24 0) (b/wu32 next-track))))

(defn- tkhd [track-id duration]
  (fbox "tkhd" 0 7
        (concat (b/wu32 0) (b/wu32 0) (b/wu32 track-id) (b/wu32 0) (b/wu32 duration)
                (b/wu32 0) (b/wu32 0) (b/wu16 0) (b/wu16 0) (b/wu16 0) (b/wu16 0)
                identity-matrix (b/wu32 0) (b/wu32 0))))

(defn- mdhd [timescale duration]
  (fbox "mdhd" 0 0 (concat (b/wu32 0) (b/wu32 0) (b/wu32 timescale) (b/wu32 duration)
                           (b/wu16 0x55c4) (b/wu16 0))))

(defn- hdlr [handler]
  (fbox "hdlr" 0 0 (concat (b/wu32 0) (b/wstr handler) (repeat 12 0)
                           (b/wstr "isobmff") (b/wu8 0))))

(defn- stts [deltas]
  (let [runs (map (fn [g] [(count g) (first g)]) (partition-by identity deltas))]
    (fbox "stts" 0 0 (concat (b/wu32 (count runs))
                             (mapcat (fn [[c d]] (concat (b/wu32 c) (b/wu32 d))) runs)))))

(defn- stsz [sizes]
  (fbox "stsz" 0 0 (concat (b/wu32 0) (b/wu32 (count sizes)) (mapcat b/wu32 sizes))))

(defn- stsc [n]
  (fbox "stsc" 0 0 (concat (b/wu32 1) (b/wu32 1) (b/wu32 n) (b/wu32 1))))

(defn- stco [offset]
  (fbox "stco" 0 0 (concat (b/wu32 1) (b/wu32 offset))))

(defn- stss [idxs]
  (fbox "stss" 0 0 (concat (b/wu32 (count idxs)) (mapcat b/wu32 (sort idxs)))))

(defn- trak [track chunk-offset]
  (let [{:keys [track-id handler timescale samples stsd]} track
        deltas    (mapv :duration samples)
        sizes     (mapv :size samples)
        duration  (reduce + 0 deltas)
        all-sync? (every? :keyframe samples)
        sync-idxs (keep-indexed (fn [i s] (when (:keyframe s) (inc i))) samples)
        stbl (box "stbl" (concat stsd
                                 (stts deltas) (stsz sizes) (stsc (count samples))
                                 (stco chunk-offset)
                                 (when-not all-sync? (stss sync-idxs))))
        mh   (if (= handler "soun") smhd vmhd)
        minf (box "minf" (concat mh dinf stbl))
        mdia (box "mdia" (concat (mdhd timescale duration) (hdlr handler) minf))]
    {:bytes (box "trak" (concat (tkhd track-id duration) mdia))
     :duration duration}))

(def ^:private ftyp
  (box "ftyp" (concat (b/wstr "isom") (b/wu32 512) (b/wstr "isom") (b/wstr "mp41"))))

(defn minimal-stsd
  "Minimal stsd box (one opaque sample entry). For R0 bootstrap/testing —
   carries no codec config (stsd is opaque per this repo's design). A real
   demux round-trip uses the source file's stsd verbatim instead."
  [fourcc]
  (let [entry (box fourcc (concat (repeat 6 0) (b/wu16 1) (repeat 16 0)))]
    (fbox "stsd" 0 0 (concat (b/wu32 1) entry))))

;; --- AAC audio sample entry (mp4a + esds) -------------------------------
;;
;; The one stsd this repo BUILDS rather than passes through. Everything else
;; here treats codec config as opaque — `minimal-stsd` carries none and a
;; remux re-emits the source's stsd verbatim — which is right for
;; re-encode-free work but leaves no way to author a track from scratch: an
;; MP4 has to declare its codec, and for AAC that declaration lives in an
;; `esds` descriptor tree. Without it, `kotoba-lang/org-iso-aac` can encode
;; AAC that ffmpeg decodes and there is still no box in which to say so
;; (com-junkawasaki/root ADR-2800002800).
;;
;; Cross-validated against the `mp4a`/`esds` that real ffmpeg wrote in this
;; repo's own `av_sample.mp4` fixture — see `test/isobmff/aac_stsd_test.clj`,
;; including the one place the two deliberately differ (ffmpeg appends the
;; optional 0x2B7 sync extension to spell out `no SBR`; this writer leaves
;; that implicit, which is what a plain AAC-LC AudioSpecificConfig means).

(defn- expandable-length
  "MPEG-4 descriptor length (ISO/IEC 14496-1 §8.3.3 `expandable`): 7 bits per
   byte, most significant group first, high bit set on every byte but the last.

   Redundant leading groups are legal and real muxers emit them — ffmpeg writes
   `80 80 80 25` for 37 — so a parser must accept them, but there is no reason
   to produce them, and this returns the compact form."
  [n]
  (let [groups (loop [v n gs ()]
                 (let [gs (conj gs (bit-and v 0x7f))
                       v (bit-shift-right v 7)]
                   (if (zero? v) gs (recur v gs))))
        last-idx (dec (count groups))]
    (vec (map-indexed (fn [i g] (if (< i last-idx) (bit-or 0x80 g) g)) groups))))

(defn- descriptor
  "One MPEG-4 descriptor: tag byte, `expandable` length, payload."
  [tag payload]
  (let [p (vec payload)]
    (into (into [tag] (expandable-length (count p))) p)))

(def ^:private es-descr-tag 0x03)
(def ^:private decoder-config-descr-tag 0x04)
(def ^:private dec-specific-info-tag 0x05)
(def ^:private sl-config-descr-tag 0x06)
(def ^:private object-type-mpeg4-audio 0x40)
(def ^:private stream-type-audio 0x05)

(defn esds
  "The `esds` box (ISO/IEC 14496-14 §5.6) for an MPEG-4 audio elementary
   stream: an ES_Descriptor wrapping a DecoderConfigDescriptor, whose
   DecoderSpecificInfo is `audio-specific-config` — the 2 bytes
   `aac.adts/audio-specific-config` produces for AAC-LC.

   `:buffer-size`/`:max-bitrate`/`:avg-bitrate` default to 0, which is what
   ffmpeg itself writes for `bufferSizeDB` and what the spec permits for an
   unsignalled rate; pass real numbers if a downstream player is known to want
   them. `:es-id` defaults to 0 (ffmpeg uses the track id; nothing in an MP4
   file reads it, since MP4 addresses streams by track)."
  [{:keys [audio-specific-config es-id buffer-size max-bitrate avg-bitrate]
    :or {es-id 0 buffer-size 0 max-bitrate 0 avg-bitrate 0}}]
  (when (empty? audio-specific-config)
    (throw (ex-info "isobmff.mux/esds: audio-specific-config is required (an AAC track with no DecoderSpecificInfo is undecodable)"
                     {})))
  (let [dsi (descriptor dec-specific-info-tag audio-specific-config)
        dcd (descriptor decoder-config-descr-tag
                        (concat (b/wu8 object-type-mpeg4-audio)
                                ;; streamType(6) << 2 | upStream(1) | reserved(1)=1
                                (b/wu8 (bit-or (bit-shift-left stream-type-audio 2) 1))
                                (b/wu24 buffer-size)
                                (b/wu32 max-bitrate)
                                (b/wu32 avg-bitrate)
                                dsi))
        ;; SLConfigDescriptor predefined = 2, "reserved for use in MP4 files"
        slc (descriptor sl-config-descr-tag (b/wu8 2))
        es (descriptor es-descr-tag
                       (concat (b/wu16 es-id)
                               (b/wu8 0)          ; no stream dependence / URL / OCR
                               dcd slc))]
    (fbox "esds" 0 0 es)))

(defn aac-stsd
  "stsd holding one `mp4a` AudioSampleEntry (ISO/IEC 14496-12 §12.2.3) with the
   `esds` above — the codec declaration an AAC track in an MP4 needs, since MP4
   carries raw access units with no ADTS header to describe them.

   `samplerate` is written as the 16.16 fixed-point field the box defines, which
   is why it is emitted as two 16-bit halves rather than a shifted 32-bit value:
   the integer part is all AAC ever needs, and shifting left by 16 overflows a
   32-bit int in ClojureScript."
  [{:keys [channel-count sample-rate] :as opts}]
  (when-not (and (pos? (or channel-count 0)) (pos? (or sample-rate 0)))
    (throw (ex-info "isobmff.mux/aac-stsd: channel-count and sample-rate are required"
                     {:channel-count channel-count :sample-rate sample-rate})))
  (let [entry (box "mp4a"
                   (concat (repeat 6 0) (b/wu16 1)          ; SampleEntry: reserved, data_reference_index
                           (b/wu32 0) (b/wu32 0)            ; AudioSampleEntry reserved[2]
                           (b/wu16 channel-count)
                           (b/wu16 16)                      ; samplesize
                           (b/wu16 0) (b/wu16 0)            ; pre_defined, reserved
                           (b/wu16 sample-rate) (b/wu16 0)  ; samplerate, 16.16 fixed
                           (esds opts)))]
    (fbox "stsd" 0 0 (concat (b/wu32 1) entry))))

(def aac-lc-samples-per-frame
  "PCM samples one AAC-LC access unit represents (`ONLY_LONG_SEQUENCE` /
   `frameLengthFlag` 0). The track's sample duration, given a timescale equal to
   the sample rate."
  1024)

(defn aac-track
  "A `mux`-shaped audio track from raw AAC `access-units` (NO ADTS headers —
   `aac.encode`'s `:access-units`, not its `:adts`) and the
   `:audio-specific-config` from the same encode.

   Timescale is the sample rate and every sample lasts
   `aac-lc-samples-per-frame`, so track time is sample-exact rather than
   rounded. Every AAC access unit is independently decodable, so all samples are
   sync samples and `mux` writes no `stss` — but note that the FIRST decoded
   frame of an AAC stream is the filterbank's priming (`aac.encode/encoder-delay`
   = 1024 samples) and this writer does not emit an edit list to hide it; a
   player will render it."
  [{:keys [track-id access-units sample-rate channel-count audio-specific-config
           samples-per-frame]
    :or {track-id 1 samples-per-frame aac-lc-samples-per-frame}}]
  {:track-id track-id
   :handler "soun"
   :timescale sample-rate
   :stsd (aac-stsd {:channel-count channel-count
                    :sample-rate sample-rate
                    :audio-specific-config audio-specific-config})
   :samples (mapv (fn [au] {:bytes (vec au)
                            :size (count au)
                            :duration samples-per-frame
                            :keyframe true})
                  access-units)})

(defn mux
  "demux structure {:timescale :tracks} → MP4 byte vector (ftyp + mdat +
   moov). Re-encode-free."
  [{:keys [tracks timescale]}]
  (let [timescale       (or timescale (:timescale (first tracks)) 1000)
        track-payloads  (mapv (fn [t] (vec (mapcat :bytes (:samples t)))) tracks)
        mdat-payload    (vec (apply concat track-payloads))
        mdat-data-start (+ (count ftyp) 8)
        offsets         (reductions + mdat-data-start (map count track-payloads))
        traks           (map trak tracks offsets)
        movie-duration  (reduce max 0 (map :duration traks))
        moov (box "moov" (concat (mvhd timescale movie-duration (inc (count tracks)))
                                 (mapcat :bytes traks)))
        mdat (box "mdat" mdat-payload)]
    (mapv #(bit-and (int %) 0xff) (concat ftyp mdat moov))))
