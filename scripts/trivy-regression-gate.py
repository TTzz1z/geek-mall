import json
import sys
from pathlib import Path


def findings(path, root=""):
    def target_name(target):
        # Trivy versions may emit absolute or scan-root-relative paths.
        normalized = target.replace("\\", "/")
        prefix = root.replace("\\", "/").rstrip("/") + "/"
        if root and normalized.startswith(prefix):
            normalized = normalized[len(prefix):]
        return normalized.removeprefix("./")

    report = json.loads(Path(path).read_text(encoding="utf-8"))
    findings_by_key = {}
    for result in report["Results"] or []:
        for item in result.get("Vulnerabilities") or []:
            severity = item["Severity"]
            if severity not in {"HIGH", "CRITICAL"}:
                continue
            key = (target_name(result["Target"]), item["PkgName"], item["VulnerabilityID"])
            if key not in findings_by_key or severity == "CRITICAL":
                findings_by_key[key] = severity
    return findings_by_key


baseline = findings(sys.argv[1], sys.argv[3] if len(sys.argv) > 3 else "")
current = findings(sys.argv[2], sys.argv[4] if len(sys.argv) > 4 else "")
regressions = [
    (key, severity)
    for key, severity in current.items()
    if key not in baseline or (severity == "CRITICAL" and baseline[key] == "HIGH")
]
for (target, package, vulnerability), severity in sorted(regressions):
    print(f"{severity} {vulnerability} {package} ({target})")
print(f"{len(regressions)} new HIGH/CRITICAL findings, {len(current) - len(regressions)} existing findings")
sys.exit(bool(regressions))
