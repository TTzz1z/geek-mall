"""Check deployed mall-web JS for showcase surface strings."""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def main() -> int:
    tmp = Path(tempfile.mkdtemp(prefix="geek-mall-assets-"))
    try:
        subprocess.check_call(
            ["docker", "cp", "geek-mall-mall-web-1:/usr/share/nginx/html/assets", str(tmp)],
            stdout=subprocess.DEVNULL,
        )
        text = "".join(p.read_text(encoding="utf-8", errors="replace") for p in tmp.rglob("*.js"))
        checks = ["继续浏览商品", "确认支付"]
        forbidden = ["Geek@123", "演示账号", "演示环境"]
        failed = False
        for s in checks:
            if s in text:
                print(f"[PASS] mall has {s}")
            else:
                print(f"[FAIL] mall missing {s}")
                failed = True
        for s in forbidden:
            if s in text:
                print(f"[FAIL] mall still contains {s}")
                failed = True
            else:
                print(f"[PASS] mall free of {s}")
        return 1 if failed else 0
    except Exception as e:
        print(f"[FAIL] mall asset check :: {e}")
        return 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
