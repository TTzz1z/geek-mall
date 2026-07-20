# Security / secret scan for public showcase (no network).
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIR_NAMES = {
    "node_modules",
    "target",
    "dist",
    ".git",
    "backups",
    "reports",
    "__pycache__",
    ".vite",
    "pnpm-store",
    ".pnpm",
    "build",
}
TEXT_EXT = {
    ".md",
    ".yml",
    ".yaml",
    ".example",
    ".ps1",
    ".sh",
    ".ts",
    ".tsx",
    ".vue",
    ".js",
    ".jsx",
    ".json",
    ".java",
    ".properties",
    ".xml",
    ".sql",
    ".txt",
    ".gitignore",
    ".scss",
    ".css",
}

PATTERNS = [
    ("openai_sk", re.compile(r"\bsk-[a-zA-Z0-9]{20,}\b")),
    ("aws_key", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("private_key_block", re.compile(r"-----BEGIN (RSA |EC )?PRIVATE KEY-----")),
    ("abs_windows_user", re.compile(r"[A-Za-z]:\\Users\\[^\\\s\"']+\\")),
    ("abs_unix_home", re.compile(r"/Users/[a-zA-Z0-9._-]+/|/home/[a-zA-Z0-9._-]+/")),
]

ALLOW_GEEK_PASS_IN = {"README.md"}


def iter_text_files():
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_NAMES and not d.startswith(".docker")]
        for name in filenames:
            if name == ".env":
                continue
            path = Path(dirpath) / name
            if path.suffix.lower() not in TEXT_EXT and name not in {".gitignore", "Dockerfile", "Dockerfile.prebuilt", "Dockerfile.runtime"}:
                continue
            yield path


def main() -> int:
    hits: list[str] = []
    env_path = ROOT / ".env"
    example = ROOT / ".env.example"

    if env_path.exists():
        text = env_path.read_text(encoding="utf-8", errors="replace")
        if re.search(r"\bsk-[a-zA-Z0-9]{20,}\b", text):
            print("[WARN] .env contains an API key-like value (local only; must stay gitignored)")
        else:
            print("[INFO] .env present; no sk- pattern found")

    if example.exists():
        et = example.read_text(encoding="utf-8", errors="replace")
        if re.search(r"\bsk-[a-zA-Z0-9]{20,}\b", et):
            hits.append(".env.example contains real-looking sk- key")
        m = re.search(r"^LLM_API_KEY=(.*)$", et, re.M)
        if m and m.group(1).strip():
            hits.append(".env.example LLM_API_KEY is not empty")
        else:
            print("[PASS] .env.example LLM_API_KEY is empty placeholder")

    for path in iter_text_files():
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        rel = path.relative_to(ROOT).as_posix()
        for name, rx in PATTERNS:
            if rx.search(text):
                hits.append(f"{rel}: {name}")
        if "Geek@123" in text and rel not in ALLOW_GEEK_PASS_IN:
            if rel.startswith("frontend/") or rel.startswith("docs/"):
                hits.append(f"{rel}: demo password in public docs/frontend")

    acc = ROOT / "docs" / "ACCEPTANCE.md"
    if acc.exists():
        at = acc.read_text(encoding="utf-8", errors="replace")
        if "Geek@123" in at or "geek_local_password" in at:
            hits.append("docs/ACCEPTANCE.md still contains password-like strings")

    if hits:
        print("[FAIL] security scan hits:")
        for h in hits[:50]:
            print("  -", h)
        if len(hits) > 50:
            print(f"  ... +{len(hits) - 50} more")
        return 1
    print("[PASS] no high-risk secrets in scanned public text files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
