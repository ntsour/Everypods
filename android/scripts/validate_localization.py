#!/usr/bin/env python3
"""Validate complete app-owned Android display and speech translations."""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res"
KOTLIN_ROOT = Path(__file__).resolve().parents[1] / "app/src/main/java"
PLACEHOLDER = re.compile(r"(?<!\\)%(?:(\d+)\$)?([sdfoxegc])")
PLURAL_QUANTITIES = {"zero", "one", "two", "few", "many", "other"}
KOTLIN_LITERAL_SINKS = (
    re.compile(r'\bText\s*\(\s*(?:(?:text)\s*=\s*)?"([^"\n]+)"'),
    re.compile(r'\bMenuNavRow\s*\(\s*"([^"\n]+)"'),
    re.compile(r'\bcontentDescription\s*=\s*"([^"\n]+)"'),
    re.compile(r'\btile\.(?:label|subtitle)\s*=\s*"([^"\n]+)"'),
    re.compile(r'\.setContent(?:Title|Text)\s*\(\s*"([^"\n]+)"'),
    re.compile(r'\bToast\.makeText\s*\([^,]+,\s*"([^"\n]+)"'),
    re.compile(r'\bNotificationChannel\s*\(\s*[^,]+,\s*"([^"\n]+)"'),
    re.compile(r'\bLocalizedSpeech\s*\(\s*"([^"\n]+)"'),
    re.compile(r'\bTtsEngine\.speak\s*\([^,]+,\s*"([^"\n]+)"'),
)


def entries(path: Path) -> tuple[dict[str, tuple[str, tuple[tuple[str, str], ...]]], list[str]]:
    root = ET.parse(path).getroot()
    found: dict[str, tuple[str, tuple[tuple[str, str], ...]]] = {}
    errors: list[str] = []
    for item in root:
        name = item.get("name")
        if not name or item.get("translatable") == "false":
            continue
        if name in found:
            errors.append(f"{path}: duplicate resource {name}")
            continue
        if item.tag == "string":
            found[name] = ("string", ())
        elif item.tag == "plurals":
            quantities = [child.get("quantity", "") for child in item]
            duplicates = sorted({quantity for quantity in quantities if quantities.count(quantity) > 1})
            errors.extend(f"{path}: plural {name} repeats quantity {quantity}" for quantity in duplicates)
            invalid = sorted(set(quantities) - PLURAL_QUANTITIES)
            errors.extend(f"{path}: plural {name} has invalid quantity {quantity}" for quantity in invalid)
            if "other" not in quantities:
                errors.append(f"{path}: plural {name} has no 'other' form")
            found[name] = ("plurals", tuple(sorted((quantity, "plural") for quantity in quantities)))
    return found, errors


def placeholder_signature(path: Path) -> dict[str, tuple[str, object]]:
    root = ET.parse(path).getroot()
    result = {}
    for item in root:
        name = item.get("name")
        if not name or item.get("translatable") == "false":
            continue
        if item.tag == "string":
            result[name] = ("string", tuple(sorted((int(position or "0"), kind) for position, kind in PLACEHOLDER.findall(item.text or ""))))
        elif item.tag == "plurals":
            result[name] = ("plurals", {
                child.get("quantity", ""): tuple(sorted((int(position or "0"), kind) for position, kind in PLACEHOLDER.findall(child.text or "")))
                for child in item
            })
    return result


def validate_kotlin_literal_sinks() -> list[str]:
    errors = []
    for path in KOTLIN_ROOT.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        for pattern in KOTLIN_LITERAL_SINKS:
            for match in pattern.finditer(text):
                literal = match.group(1)
                visible_text = re.sub(r"\\u[0-9a-fA-F]{4}|\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_]*", "", literal)
                if not re.search(r"[A-Za-z]{2,}", visible_text):
                    continue
                # StyledTogglePreview is a design-tool fixture, not app content.
                if path.name == "StyledToggle.kt" and literal in {
                    "Example Toggle",
                    "This is an example description for the styled toggle.",
                }:
                    continue
                line = text.count("\n", 0, match.start()) + 1
                errors.append(f"{path}:{line}: user-facing Kotlin literal must use a string resource: {literal}")
    return errors


def main() -> int:
    errors: list[str] = validate_kotlin_literal_sinks()
    default_files = {name: ROOT / "values" / name for name in ("strings.xml", "speech.xml")}
    defaults = {}
    default_placeholders = {}
    for name, path in default_files.items():
        if not path.is_file():
            errors.append(f"Missing default resource file: {path}")
            continue
        defaults[name], found_errors = entries(path)
        errors.extend(found_errors)
        default_placeholders[name] = placeholder_signature(path)

    locale_pattern = re.compile(r"values-[a-zA-Z]{2,3}(?:-r[A-Z]{2}|-b\+[A-Za-z0-9+]+)?")
    candidates = [
        path for path in ROOT.glob("values-*")
        if path.is_dir() and any((path / filename).is_file() for filename in ("strings.xml", "speech.xml"))
    ]
    malformed = [path for path in candidates if not locale_pattern.fullmatch(path.name)]
    errors.extend(f"Malformed locale resource folder: {path.name}" for path in malformed)
    locales = sorted(
        path for path in candidates
        if locale_pattern.fullmatch(path.name)
    )
    for path in (ROOT / "layout").rglob("*.xml"):
        try:
            layout = ET.parse(path).getroot()
        except ET.ParseError as exc:
            errors.append(f"{path}: malformed layout XML: {exc}")
            continue
        for item in layout.iter():
            literal = item.get("{http://schemas.android.com/apk/res/android}text")
            if literal and not literal.startswith("@"):
                errors.append(f"{path}: user-facing layout text must use a string resource: {literal}")
    for folder in locales:
        for filename in ("strings.xml", "speech.xml"):
            path = folder / filename
            if not path.exists():
                errors.append(f"{folder.name} has no {filename}")
                continue
            parsed, found_errors = entries(path)
            errors.extend(found_errors)
            unknown = parsed.keys() - defaults.get(filename, {}).keys()
            missing = defaults.get(filename, {}).keys() - parsed.keys()
            errors.extend(f"{path}: unknown resource {key}" for key in sorted(unknown))
            errors.extend(f"{path}: missing translation for {key}" for key in sorted(missing))
            localized_placeholders = placeholder_signature(path)
            for key in parsed.keys() & default_placeholders.get(filename, {}).keys():
                if parsed[key][0] != defaults[filename][key][0]:
                    errors.append(f"{path}: resource type mismatch for {key}")
                    continue
                expected_type, expected = default_placeholders[filename][key]
                actual_type, actual = localized_placeholders.get(key, ("", None))
                if expected_type == "string" and expected != actual:
                    errors.append(f"{path}: placeholder mismatch for {key}: {expected} != {actual}")
                elif expected_type == "plurals":
                    required_signature = expected.get("other", ())
                    for quantity, signature in actual.items():
                        if signature != required_signature:
                            errors.append(f"{path}: placeholder mismatch for {key}/{quantity}: {required_signature} != {signature}")

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Validated complete display and speech resources for {len(locales)} locale folders.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
