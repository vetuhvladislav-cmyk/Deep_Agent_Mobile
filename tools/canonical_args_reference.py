#!/usr/bin/env python3
"""Reference implementation for CanonicalArgs v1.

This is intentionally independent from the Kotlin implementation. It validates
the committed golden vectors and rejects duplicate keys and non-finite numbers.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from decimal import Decimal
from pathlib import Path
from typing import Any


def reject_constant(value: str) -> Any:
    raise ValueError("INVALID_VALUE:" + value)


def object_no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("DUPLICATE_KEY:" + key)
        result[key] = value
    return result


def parse(raw: str) -> Any:
    return json.loads(
        raw,
        object_pairs_hook=object_no_duplicates,
        parse_int=Decimal,
        parse_float=Decimal,
        parse_constant=reject_constant,
    )


def canonical_number(value: Decimal) -> str:
    if not value.is_finite():
        raise ValueError("INVALID_NUMBER")
    if value == 0:
        return "0"
    result = format(value.normalize(), "f")
    if len(result) > 256:
        raise ValueError("INVALID_NUMBER")
    return result


def canonical_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def canonical(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, Decimal):
        return canonical_number(value)
    if isinstance(value, str):
        return canonical_string(value)
    if isinstance(value, list):
        return "[" + ",".join(canonical(item) for item in value) + "]"
    if isinstance(value, dict):
        keys = sorted(
            value,
            key=lambda item: item.encode("utf-16-be", "surrogatepass"),
        )
        return "{" + ",".join(
            canonical_string(key) + ":" + canonical(value[key])
            for key in keys
        ) + "}"
    raise TypeError(type(value).__name__)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("vectors", type=Path)
    args = parser.parse_args()
    document = json.loads(args.vectors.read_text(encoding="utf-8"))
    failures: list[str] = []

    for vector in document["vectors"]:
        actual = canonical(parse(vector["input"])).encode("utf-8")
        if actual.hex() != vector["canonical_bytes_hex"]:
            failures.append(vector["name"] + ": canonical bytes")
        if hashlib.sha256(actual).hexdigest() != vector["sha256"]:
            failures.append(vector["name"] + ": SHA-256")

    for item in document["invalid_cases"]:
        try:
            canonical(parse(item["raw"]))
        except ValueError:
            continue
        failures.append(item["name"] + ": rejection missing")

    if failures:
        raise SystemExit("\\n".join(failures))
    print("CanonicalArgs v1 reference vectors: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
