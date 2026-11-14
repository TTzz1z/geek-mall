"""Validate every documented event sample and prove required fields are enforced."""
import copy
import json
from pathlib import Path

from jsonschema import Draft202012Validator

root = Path(__file__).resolve().parents[1] / "contracts" / "events"
schema = json.loads((root / "producer-event.schema.json").read_text(encoding="utf-8"))
legacy = Draft202012Validator(json.loads((root / "domain-event.schema.json").read_text(encoding="utf-8")))
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema)
allowed = set(schema["properties"]["eventType"]["enum"])
samples = sorted((root / "samples").glob("*.json"))
assert samples, "No event samples found"
seen = set()
for path in samples:
    event = json.loads(path.read_text(encoding="utf-8"))
    assert legacy.is_valid(event), f"{path}: rejected by V1 consumer schema"
    errors = list(validator.iter_errors(event))
    assert not errors, f"{path}: {errors}"
    event_type = event["eventType"]
    assert path.name == f"{event_type}.json" and event_type not in seen, path
    seen.add(event_type)
    for field in schema["required"]:
        invalid = copy.deepcopy(event)
        del invalid[field]
        assert not validator.is_valid(invalid), f"{path}: missing {field} accepted"
    rule = next(rule for rule in schema["allOf"] if rule["if"]["properties"]["eventType"]["const"] == event_type)
    for field in rule["then"]["properties"]["payload"]["required"]:
        invalid = copy.deepcopy(event)
        del invalid["payload"][field]
        assert not validator.is_valid(invalid), f"{path}: missing payload.{field} accepted"
    print(f"PASS {path.name}")
assert seen == allowed, f"Sample coverage mismatch: missing={allowed-seen}, unexpected={seen-allowed}"
