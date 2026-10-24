#!/usr/bin/env python3
"""Render every ```mermaid block in hardware.md and software.md to rendered/<file>-<n>.svg.

Labels are plain SVG <text> (htmlLabels off), so the SVGs display in any viewer, not only browsers.
Run: nix shell nixpkgs#mermaid-cli -c python3 docs/flows/render.py
"""
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).parent
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
        for md in ("hardware.md", "software.md"):
            blocks = re.findall(r"```mermaid\n(.*?)```", (HERE / md).read_text(), re.S)
            for i, b in enumerate(blocks, 1):
                src = tdp / f"{md}-{i}.mmd"
                src.write_text(b)
                out = HERE / "rendered" / f"{md[:-3]}-{i}.svg"
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
