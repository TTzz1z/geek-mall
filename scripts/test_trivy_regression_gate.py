"""Exercise path normalization and severity regressions in the Trivy gate."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

GATE = Path(__file__).with_name("trivy-regression-gate.py")

class GateTest(unittest.TestCase):
    def test_baseline_path_and_escalation(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("ACCEPTANCE_TEMP_DIR")) as directory:
            root = Path(directory)
            def report(name, target, severity):
                path = root / name
                path.write_text(json.dumps({"Results": [{"Target": target, "Vulnerabilities": [
                    {"PkgName": "dep", "VulnerabilityID": "CVE-TEST", "Severity": severity}
                ]}]}), encoding="utf-8")
                return path
            base = report("base.json", "/base/backend/pom.xml", "HIGH")
            head = report("head.json", "/head/backend/pom.xml", "HIGH")
            args = [sys.executable, str(GATE), str(base), str(head), "/base", "/head"]
            self.assertEqual(subprocess.run(args, capture_output=True).returncode, 0)
            report("head.json", "/head/backend/pom.xml", "CRITICAL")
            self.assertEqual(subprocess.run(args, capture_output=True).returncode, 1)
            report("head.json", "/head/other/pom.xml", "HIGH")
            self.assertEqual(subprocess.run(args, capture_output=True).returncode, 1)

if __name__ == "__main__":
    unittest.main()
