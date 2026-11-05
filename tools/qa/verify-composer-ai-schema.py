"""Validate actual Java test wire output with the checked-out AI #570 Pydantic models.

Run using an existing AI Python environment. Imports models only, never Settings,
an ASGI application, a provider bridge, credentials or a network client.
"""
import hashlib
import json
from pathlib import Path
import sys
from types import ModuleType

ROOT = Path(__file__).resolve().parents[2]
# The normal package __init__ imports the ASGI factory. Model-only namespaces avoid
# that unrelated startup while executing the unmodified model/base source files.
for name, folder in [("edupilot_ai", "ai-service/src/edupilot_ai"),
    ("edupilot_ai.models", "ai-service/src/edupilot_ai/models")]:
    package = ModuleType(name)
    package.__path__ = [str(ROOT / folder)]
    sys.modules[name] = package

from pydantic import ValidationError
from edupilot_ai.models.composer_prediction import ComposerPredictionRequest, ComposerPredictionResponse


def main():
    folder = ROOT / "main-service/build/composer-wire"
    expected = {"schemaVersion", "sourceTurnId", "contextVersion", "suggestion", "warnings", "sourceMessageId"}
    source_files = ["ai-service/src/edupilot_ai/models/base.py", "ai-service/src/edupilot_ai/models/composer_prediction.py"]
    payload_hashes = {}
    request_path = folder / "internal-request.json"
    request_bytes = request_path.read_bytes()
    request = ComposerPredictionRequest.model_validate_json(request_bytes)
    assert request.schema_version == "1.0" and request.source_turn_id == "message-4"
    payload_hashes[request_path.name] = hashlib.sha256(request_bytes).hexdigest()
    checked = ["JAVA_INTERNAL_REQUEST_ACCEPTED_BY_ACTUAL_AI_MODEL"]
    for name in ["accepted.json", "unavailable.json", "gated.json"]:
        path = folder / name
        raw = path.read_bytes()
        value = json.loads(raw)
        assert set(value) == expected and value["schemaVersion"] == "1.0"
        assert value["sourceTurnId"] == f"message-{value['sourceMessageId']}"
        assert "usage" not in value and "reason" not in value
        shared = {key: item for key, item in value.items() if key != "sourceMessageId"}
        try:
            ComposerPredictionResponse.model_validate(value)
        except ValidationError:
            checked.append(f"{name}:PUBLIC_SELECTOR_IS_NOT_AN_INTERNAL_AI_FIELD")
        else:
            raise AssertionError("Public projection must not masquerade as the strict AI response")
        if name == "gated.json":
            assert value["contextVersion"] is None and value["suggestion"] is None and value["warnings"] == []
            try:
                ComposerPredictionResponse.model_validate(shared)
            except ValidationError:
                checked.append("GATED_NULL_CONTEXT_IS_EXPLICITLY_PUBLIC_ONLY")
            else:
                raise AssertionError("Internal contextVersion must remain non-null")
        else:
            validated = ComposerPredictionResponse.model_validate(shared)
            assert validated.source_turn_id == value["sourceTurnId"] and validated.context_version == value["contextVersion"]
            assert validated.usage is None
            if name == "accepted.json":
                assert validated.suggestion and validated.warnings == []
            else:
                assert validated.suggestion is None and [w.type for w in validated.warnings] == ["PREDICTION_UNAVAILABLE"]
            checked.append(f"{name}:SHARED_FIELDS_ACCEPTED_BY_ACTUAL_AI_MODEL")
        payload_hashes[name] = hashlib.sha256(raw).hexdigest()
    report = {"status": "PASSED", "scope": "synthetic-model-validation-only;not-provider-or-deployed-E2E",
        "checks": checked, "wireSha256": payload_hashes,
        "aiModelSha256Lf": {name: hashlib.sha256((ROOT / name).read_bytes().replace(b"\r\n", b"\n")).hexdigest() for name in source_files}}
    (folder / "ai-model-validation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PASSED", "checks": len(checked), "providerCalls": 0}))


if __name__ == "__main__":
    main()
