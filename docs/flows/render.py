#!/usr/bin/env python3
"""Render every ```mermaid block in hardware.md, software.md and docs/research/*.md to <dir>/rendered/<file>-<n>.svg.

Labels are plain SVG <text> (htmlLabels off), so the SVGs display in any viewer, not only browsers.
Run: nix shell nixpkgs#mermaid-cli -c python3 docs/flows/render.py
Pass Markdown paths to render only those files (e.g. docs/research/*.md).
"""
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).parent
RESEARCH = HERE.parent / "research"
CONFIG = {"htmlLabels": False, "flowchart": {"htmlLabels": False}, "class": {"htmlLabels": False},
          "state": {"htmlLabels": False}}


def main() -> int:
    chrome = shutil.which("chromium") or shutil.which("google-chrome")
    with tempfile.TemporaryDirectory() as td:
        tdp = pathlib.Path(td)
        (tdp / "cfg.json").write_text(json.dumps(CONFIG))
        pp = {"args": ["--no-sandbox"], **({"executablePath": chrome} if chrome else {})}
        (tdp / "pp.json").write_text(json.dumps(pp))
        n_ok = 0
        sources = ([pathlib.Path(arg) for arg in sys.argv[1:]] if len(sys.argv) > 1 else
                   [HERE / "hardware.md", HERE / "software.md", *sorted(RESEARCH.glob("*.md"))])
        for md in sources:
            blocks = re.findall(r"```mermaid\n(.*?)```", md.read_text(), re.S)
            for i, b in enumerate(blocks, 1):
                src = tdp / f"{md.stem}-{i}.mmd"
                src.write_text(b)
                out = md.parent / "rendered" / f"{md.stem}-{i}.svg"
                out.parent.mkdir(exist_ok=True)
                r = subprocess.run(["mmdc", "-q", "-p", str(tdp / "pp.json"), "-c", str(tdp / "cfg.json"),
                                    "-i", str(src), "-o", str(out)], capture_output=True, text=True)
                if r.returncode:
                    print(f"FAIL {md} block {i}\n{r.stderr}", file=sys.stderr)
                    return 1
                n_ok += 1
        print(f"rendered {n_ok} diagrams")
    return 0


if __name__ == "__main__":
    sys.exit(main())
