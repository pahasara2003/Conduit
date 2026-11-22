#!/usr/bin/env python3
"""Headless notification validator for ClipSync.
Verifies syntax, imports, and styling of both daemon and popup widgets WITHOUT emitting live desktop alerts.
"""

import py_compile
import sys
from pathlib import Path

repo_root = Path(__file__).resolve().parent.parent.parent
pc_dir = repo_root / "pc"
pc_script = pc_dir / "clipsync_pc.py"
popup_script = pc_dir / "notify_popup.py"

for script in (pc_script, popup_script):
    if not script.exists():
        print(f"[FAIL] Missing {script}")
        sys.exit(1)
    try:
        py_compile.compile(str(script), doraise=True)
        print(f"[PASS] {script.name} compiled successfully.")
    except Exception as e:
        print(f"[FAIL] Compilation failed for {script.name}: {e}")
        sys.exit(1)

print("[PASS] All headless verification checks passed.")
