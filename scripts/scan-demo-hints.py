"""Scan user-facing frontend sources for demo/test credential hints."""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCAN_ROOTS = [
    ROOT / "frontend/apps/mall-web/src",
    ROOT / "frontend/apps/admin-web/src",
    ROOT / "frontend/apps/ops-screen/src",
    ROOT / "frontend/apps/mobile-uniapp/src",
]
EXCLUDE_DIRS = {"_archived", "_spa_backup", "node_modules", "dist", "build"}
INCLUDE_EXT = {".vue", ".ts", ".tsx", ".js", ".jsx", ".scss", ".css", ".html"}
PATTERNS = [
    ("Geek@123", re.compile(r"Geek@123")),
    ("演示账号", re.compile(r"演示账号")),
    ("演示环境", re.compile(r"演示环境")),
    ("演示密码", re.compile(r"演示密码")),
    ("演示买家", re.compile(r"演示买家")),
    ("演示负例", re.compile(r"演示负例")),
    ("预填演示账号", re.compile(r"shopper\s*/\s*Geek")),
]


def iter_files(root: Path):
    if not root.exists():
        print(f"[SKIP] missing {root.relative_to(ROOT)}")
        return
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if any(part in EXCLUDE_DIRS for part in path.parts):
            continue
        if path.suffix.lower() not in INCLUDE_EXT:
            continue
        yield path


def scan_sources() -> list[tuple[str, str]]:
    hits: list[tuple[str, str]] = []
    for root in SCAN_ROOTS:
        for path in iter_files(root):
            text = path.read_text(encoding="utf-8", errors="replace")
            rel = path.relative_to(ROOT).as_posix()
            for name, rx in PATTERNS:
                if rx.search(text):
                    hits.append((name, rel))
    return hits


def scan_deployed() -> list[str]:
    bad: list[str] = []
    forbidden = ["Geek@123", "演示账号", "演示环境", "演示密码"]
    for container, label in (
        ("geek-mall-mall-web-1", "mall-web"),
        ("geek-mall-admin-web-1", "admin-web"),
    ):
        try:
            out = subprocess.check_output(
                [
                    "docker",
                    "exec",
                    container,
                    "sh",
                    "-c",
                    "cat /usr/share/nginx/html/assets/*.js 2>/dev/null || true",
                ],
                stderr=subprocess.DEVNULL,
            )
        except Exception:
            print(f"[SKIP] deploy check {label}")
            continue
        # docker may return non-utf8; decode loosely then search unicode needles
        text = out.decode("utf-8", errors="replace")
        for needle in forbidden:
            if needle in text:
                bad.append(f"{label}:{needle}")
    return bad


def main() -> int:
    print("== source scan ==")
    hits = scan_sources()
    if not hits:
        print("[PASS] no forbidden demo hints in frontend src")
    else:
        print(f"[FAIL] {len(hits)} hit(s):")
        for name, rel in hits:
            print(f"  - [{name}] {rel}")

    print()
    print("== deployed assets ==")
    bad = scan_deployed()
    if not bad:
        print("[PASS] mall/admin container assets clean (or containers unavailable)")
    else:
        print("[FAIL] deployed assets still contain demo hints:")
        for item in bad:
            print(f"  - {item}")

    failed = bool(hits or bad)
    print()
    print("scan-demo-hints: " + ("FAILED" if failed else "OK"))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
