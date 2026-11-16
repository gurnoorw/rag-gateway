#!/usr/bin/env python3
"""Validates a summary against bench/summary.schema.json (t08):  schema_check.py <summary.json> [<schema.json>]

No third-party dependency, so the laptop run needs nothing installed: a checker for exactly the JSON-Schema
keywords the schema uses. A keyword it does not know is an error, so a schema change cannot be ignored silently.
When the `jsonschema` package is installed, it validates too (Draft 2020-12, with the date format checked).
Exit 0 when valid; otherwise every error is printed and the exit code is 1.
"""
import datetime
import json
import os
import re
import sys

ANNOTATIONS = {"$schema", "title", "description", "$defs"}
TYPES = {
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
    "string": lambda v: isinstance(v, str),
    "boolean": lambda v: isinstance(v, bool),
    "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
}


def check(value, schema, root, path, errors):
    if "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/$defs/"):
            errors.append(f"{path}: unsupported $ref {ref}")
            return
        check(value, root["$defs"][ref[len("#/$defs/"):]], root, path, errors)
        return
    for key in schema:
        if key in ANNOTATIONS or key in {"type", "required", "additionalProperties", "properties", "minimum",
                                         "maximum", "exclusiveMinimum", "const", "pattern", "minLength",
                                         "minItems", "items", "format"}:
            continue
        errors.append(f"{path}: schema keyword '{key}' is not supported by this checker")
    t = schema.get("type")
    if t is not None and not TYPES[t](value):
        errors.append(f"{path}: expected {t}, got {type(value).__name__}")
        return
    if "const" in schema and value != schema["const"]:
        errors.append(f"{path}: must be {schema['const']!r}")
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            errors.append(f"{path}: {value} < minimum {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            errors.append(f"{path}: {value} > maximum {schema['maximum']}")
        if "exclusiveMinimum" in schema and value <= schema["exclusiveMinimum"]:
            errors.append(f"{path}: {value} <= exclusiveMinimum {schema['exclusiveMinimum']}")
    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            errors.append(f"{path}: shorter than {schema['minLength']}")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            errors.append(f"{path}: does not match {schema['pattern']}")
        if schema.get("format") == "date":
            try:
                datetime.date.fromisoformat(value)
                if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
                    raise ValueError
            except ValueError:
                errors.append(f"{path}: not a date (YYYY-MM-DD)")
    if isinstance(value, dict):
        for req in schema.get("required", []):
            if req not in value:
                errors.append(f"{path}: missing required '{req}'")
        props = schema.get("properties", {})
        for k, v in value.items():
            if k in props:
                check(v, props[k], root, f"{path}.{k}", errors)
            elif schema.get("additionalProperties") is False:
                errors.append(f"{path}: property '{k}' is not allowed")
    if isinstance(value, list):
        if "minItems" in schema and len(value) < schema["minItems"]:
            errors.append(f"{path}: fewer than {schema['minItems']} items")
        if "items" in schema:
            for i, item in enumerate(value):
                check(item, schema["items"], root, f"{path}[{i}]", errors)


def validate(summary, schema):
    errors = []
    check(summary, schema, schema, "$", errors)
    try:
        import jsonschema  # optional second opinion
    except ImportError:
        return errors
    validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
    errors += [f"jsonschema: {'/'.join(map(str, e.path)) or '$'}: {e.message}" for e in validator.iter_errors(summary)]
    return errors


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit("usage: schema_check.py <summary.json> [<schema.json>]")
    schema_path = sys.argv[2] if len(sys.argv) == 3 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "summary.schema.json")
    with open(sys.argv[1]) as f:
        summary = json.load(f)
    with open(schema_path) as f:
        schema = json.load(f)
    errors = validate(summary, schema)
    for e in errors:
        print(e, file=sys.stderr)
    if errors:
        sys.exit(1)
    print(f"{sys.argv[1]}: valid against {os.path.normpath(schema_path)}")


if __name__ == "__main__":
    main()
