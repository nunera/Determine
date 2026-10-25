# Determine — Inside the simulation

[Open the illustrated PDF](Determine-Simulation-and-Glasses.pdf)

13 pages covering the running simulator, physical headband architecture, real-engine loop, one-zone sensor model, recorded warning trace, TTC and output latency, failure states, proposed glasses integration, camera crop/resampling, power/bandwidth/privacy implications, AWS placement and a validation plan. Includes 13 numbered figures, clickable contents, PDF bookmarks and linked sources.

## Editable source

- `build.py`: text, print layout, diagrams, tables, source links and computed figures.
- `assets/simulation.png`: prior actual production-demo screenshot. The figure uses an aspect-preserving scene crop and a labelled status transcription, not a fabricated new UI.
- `assets/sign-trace.json`: portable sensor/alert snapshot from the recorded sign episode. Contains upstream tuning, timestamps, provenance and the SHA-256 of the original 872-line JSONL file. This is a simulator recording, not hardware data.
- `build-metadata.json`: PDF hash, source fingerprint and values recomputed by the build.
- `inspect_pdf.py`: renders every page and checks text bounds/internal-link destinations. It is a document-production check, not a product-safety test.

## Rebuild

Requires Python 3, Fontconfig (`fc-match`) and DejaVu Sans / Sans Mono fonts. Source paths are relative to this directory; no running simulator, credentials, Aria dataset or cloud endpoint is required.

```sh
python3 -m venv .venv-pdf
.venv-pdf/bin/pip install -r docs/visual-guide/requirements.txt
.venv-pdf/bin/python docs/visual-guide/build.py
.venv-pdf/bin/python docs/visual-guide/inspect_pdf.py /tmp/determine-pdf-preview
```

On NixOS, PyMuPDF's wheel may need the GCC C++ runtime on `LD_LIBRARY_PATH`; use the installed compiler runtime's `lib` directory. This native-library requirement affects preview inspection, not ReportLab's PDF build.

## Evidence / inspection

The document separates implemented code, recorded local observations, mathematical/sensor assumptions and proposed integrations. Its 235 ms interval is between two recorder receipt timestamps; it is explicitly not an acoustic latency claim. The glasses export is explicitly not a DAT SDK integration or validated optical model.

Every page was rendered and visually reviewed. Layout review caught and corrected the cover callout overlap and a wrapped section-title collision. Final checks covered 13 pages and bookmarks, cover navigation, external link annotations, text bounds, source-derived trace values, crop geometry and decoded-RGB arithmetic. External source annotations being valid PDF links does not guarantee a third-party URL will remain reachable.
