"""Verify generated Java row identities match the full versioned specification metadata."""
import json
import re
from pathlib import Path

root = next(p for p in Path(__file__).resolve().parents if (p / "rules/report-dictionary.json").exists())
metadata = json.loads((root / "rules/report-dictionary.json").read_text(encoding="utf-8"))
java = (root / "backend/src/main/java/com/lumira/finance/domain/ReportDictionary.java").read_text(encoding="utf-8")
literal = r'"(?:[^"\\]|\\.)*"'
java_rows = [tuple(json.loads(v) for v in match) for match in
             re.findall(r'new Row\(\s*(' + literal + r')\s*,\s*(' + literal + r')\s*,\s*(' + literal + r')\s*\)', java)]
spec_rows = [(r["template"], r["rowId"], r["label"]) for r in metadata["rows"]]
assert len(spec_rows) == len(set((r[0], r[1]) for r in spec_rows)) == 256
assert java_rows == spec_rows
assert metadata["status"] == "UNREVIEWED" and metadata["publicationAllowed"] is False
assert all(r["reviewStatus"] == "UNREVIEWED" and r["specification"] for r in metadata["rows"])
print("256 report metadata identities and labels agree; publication remains blocked")
