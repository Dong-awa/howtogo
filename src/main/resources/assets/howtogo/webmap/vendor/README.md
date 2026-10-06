# Vendored front-end libraries

## html2canvas 1.4.1

- File: `html2canvas.min.js` (served at `/vendor/html2canvas.min.js`)
- Source: <https://cdn.jsdelivr.net/npm/html2canvas@1.4.1/dist/html2canvas.min.js>
- Licence: MIT — Copyright (c) 2022 Niklas von Hertzen <https://hertzen.com>
- SHA-256: `E87E550794322E574A1FDA0C1549A3C70DAE5A93D9113417A429016838EAB8CB`
- Size: 198,689 bytes

Why it is shipped in the jar rather than loaded from a CDN: the browser map is served from
`http://127.0.0.1:<port>` and has to work with no internet at all — a Minecraft session on a train
is exactly when a player wants their map. A CDN `<script>` would also make the page silently
dependent on a third party, and the export button is the feature this library exists for.

The file is byte-for-byte the published build, including its own licence header; do not edit it.
