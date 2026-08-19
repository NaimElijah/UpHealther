#!/usr/bin/env python3
"""Regenerates the mechanical diagrams in this folder's README from the source they describe.

Three of the six diagrams are derived rather than drawn, because they restate something the code
already states and would otherwise drift the moment anyone adds an import, a field or a column:

    context-map    the cross-context dependency graph, from the import statements
    class-diagram  one bounded context's types and their relations, from the package layout
    er-diagram     the database schema, from the Flyway migrations

The other three are conceptual, do not rot, and are written by hand in the README.

Usage:
    python generate.py            rewrite the generated regions of README.md
    python generate.py --check    exit 1 if the committed README is out of date

The generated regions are delimited by <!-- generated:NAME --> ... <!-- /generated:NAME --> markers;
everything outside them is left untouched.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

# The context whose internals the class diagram shows. `upgrade` owns the core aggregate and its
# state machine, so it is the one worth reading in full; every other context follows the same shape.
CLASS_DIAGRAM_CONTEXT = "upgrade"

CONTEXTS = [
    "auth", "user", "healtharea", "upgrade", "tracking",
    "reflection", "reminder", "dashboard", "notification",
]

# Package path (relative to a context root) → the layer and role shown as a Mermaid stereotype.
# Longest match wins, so `domain/port/out` beats `domain`.
STEREOTYPES = [
    ("adapter/in/web", "web adapter"),
    ("adapter/in/scheduling", "scheduling adapter"),
    ("adapter/in/composition", "composition adapter"),
    ("adapter/in/event", "event adapter"),
    ("adapter/out/persistence", "persistence adapter"),
    ("adapter/out/messaging", "messaging adapter"),
    ("application/port/in", "inbound port"),
    ("application/port/out", "outbound port"),
    ("application", "application"),
    ("domain/model", "domain model"),
    ("domain/event", "domain event"),
    ("domain/port/out", "outbound port"),
    ("domain/service", "domain service"),
]

SQL_TYPE_TOKENS = {
    "UUID": "uuid", "TEXT": "text", "DATE": "date", "BOOLEAN": "boolean",
    "INTEGER": "integer", "BIGINT": "bigint", "TIMESTAMP": "timestamp",
    "DOUBLE": "double", "VARCHAR": "varchar", "NUMERIC": "numeric",
}


# --------------------------------------------------------------------------- source discovery

def repo_root() -> Path:
    for candidate in [Path(__file__).resolve()] + list(Path(__file__).resolve().parents):
        if (candidate / ".git").exists():
            return candidate
    raise SystemExit("could not locate the repository root (no .git found above this file)")


ROOT = repo_root()
JAVA_ROOT = ROOT / "backend" / "src" / "main" / "java" / "com" / "healthupgrades"
MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"
README = Path(__file__).resolve().parent / "README.md"

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT = re.compile(r"//[^\n]*")
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')


def code_only(source: str) -> str:
    """Strips comments and string literals, so documentation references are not read as dependencies.

    This matters here: the codebase is heavily javadoc'd and nearly every class names several others
    in prose. Scanning the raw text would report those mentions as relations.
    """
    return STRING_LITERAL.sub('""', LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", source)))


def stereotype_for(relative_package: str, kind: str, name: str) -> str:
    """The layer and role to stamp on a type, from where it sits, what kind it is, and what it is called.

    Two distinctions the package path alone would flatten, both of which the hexagonal layering exists
    to make visible: a port package holds the port interface *and* the records passed across it, and
    the application package holds use cases *and* the configuration that hand-wires the domain
    services — which carry no stereotype annotation precisely so the domain stays framework-free.
    """
    best, label = "", "type"
    for prefix, candidate in STEREOTYPES:
        if relative_package.startswith(prefix) and len(prefix) > len(best):
            best, label = prefix, candidate

    if label in ("inbound port", "outbound port") and kind != "interface":
        return "use-case record" if "port/in" in relative_package else "port record"
    if label == "application" and name.endswith("Config"):
        return "bean wiring"
    return label


def discover_types() -> dict:
    """Every type in the backend's main sources, keyed by simple name."""
    types = {}
    for path in sorted(JAVA_ROOT.rglob("*.java")):
        relative = path.relative_to(JAVA_ROOT)
        parts = relative.parts
        context = parts[0]
        package = "/".join(parts[1:-1])
        name = path.stem
        source = path.read_text(encoding="utf-8")
        body = code_only(source)

        kind = "class"
        for candidate in ("interface", "record", "enum"):
            if re.search(r"\b%s\s+%s\b" % (candidate, re.escape(name)), body):
                kind = candidate
                break

        types[name] = {
            "context": context,
            "package": package,
            "kind": kind,
            "stereotype": stereotype_for(package, kind, name),
            "body": body,
            "source": source,
        }
    return types


# --------------------------------------------------------------------------- 1. context map

def context_map() -> str:
    """The cross-context dependency graph, read off the imports rather than off intent."""
    edges = {}
    for path in sorted(JAVA_ROOT.rglob("*.java")):
        source_context = path.relative_to(JAVA_ROOT).parts[0]
        if source_context not in CONTEXTS:
            continue
        for match in re.finditer(r"^import\s+(?:static\s+)?com\.healthupgrades\.(\w+)\.",
                                 path.read_text(encoding="utf-8"), re.MULTILINE):
            target = match.group(1)
            if target in CONTEXTS and target != source_context:
                edges.setdefault(source_context, set()).add(target)

    depended_on = {t for targets in edges.values() for t in targets}
    lines = ["flowchart TD"]
    for context in CONTEXTS:
        if context in edges or context in depended_on:
            shape = f'    {context}["{context}"]'
            lines.append(shape)
    lines.append("")
    for source in CONTEXTS:
        for target in sorted(edges.get(source, ())):
            lines.append(f"    {source} --> {target}")
    lines.append("")
    sinks = sorted(c for c in depended_on if c not in edges)
    lines.append(f"    %% depends on nothing: {', '.join(sinks)} — the graph is acyclic,")
    lines.append("    %% which HexagonalArchitectureTest fails the build over if it stops being true.")
    return "\n".join(lines)


# --------------------------------------------------------------------------- 2. class diagram

FIELD = re.compile(
    r"^\s*(?:private|protected|public)\s+(?:static\s+|final\s+|transient\s+|volatile\s+)*"
    r"([A-Za-z_][\w.]*(?:\s*<[^;=]*?>)?)\s+([a-z]\w*)\s*[;=]",
    re.MULTILINE,
)
IMPLEMENTS = re.compile(r"\b(?:class|record|enum)\s+\w+[^{]*?\bimplements\s+([\w\s,<>.]+?)\s*\{", re.DOTALL)
EXTENDS = re.compile(r"\b(?:class|interface)\s+\w+[^{]*?\bextends\s+([\w\s,<>.]+?)(?:\s+implements|\s*\{)", re.DOTALL)


def record_components(body: str, name: str) -> str:
    """The parameter list of a record declaration, or an empty string for anything else."""
    start = body.find(f"record {name}")
    if start == -1:
        return ""
    open_paren = body.find("(", start)
    if open_paren == -1:
        return ""
    depth, index = 0, open_paren
    while index < len(body):
        if body[index] == "(":
            depth += 1
        elif body[index] == ")":
            depth -= 1
            if depth == 0:
                return body[open_paren + 1:index]
        index += 1
    return ""


def referenced_types(text: str, known: set, exclude: str) -> set:
    """Project types named in a fragment of code — generic arguments included."""
    return {token for token in re.findall(r"\b([A-Z]\w+)\b", text) if token in known and token != exclude}


def supertype_names(clause: str, known: set, exclude: str) -> set:
    """The base types in an extends/implements clause, ignoring their type arguments.

    `extends JpaRepository<HealthUpgrade, UUID>` names one supertype, not two: reading the type
    argument as a supertype would draw an inheritance arrow that does not exist.
    """
    parts, depth, current = [], 0, ""
    for character in clause:
        if character == "<":
            depth += 1
        elif character == ">":
            depth -= 1
        if character == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += character
    parts.append(current)

    names = {part.split("<")[0].strip().split(".")[-1] for part in parts}
    return {name for name in names if name in known and name != exclude}


def class_diagram(types: dict, context: str) -> str:
    members = {name: meta for name, meta in types.items() if meta["context"] == context}
    known = set(members)

    lines = ["classDiagram", "    direction LR", ""]

    by_stereotype = {}
    for name, meta in sorted(members.items()):
        by_stereotype.setdefault(meta["stereotype"], []).append((name, meta))

    layer_order = ["domain model", "domain event", "domain service", "outbound port", "port record",
                   "application", "bean wiring", "inbound port", "use-case record",
                   "web adapter", "scheduling adapter", "persistence adapter",
                   "composition adapter", "messaging adapter", "event adapter"]
    ordered = sorted(by_stereotype, key=lambda s: (layer_order.index(s) if s in layer_order else 99, s))

    for stereotype in ordered:
        lines.append(f"    %% ---- {stereotype} ----")
        for name, meta in by_stereotype[stereotype]:
            label = meta["stereotype"] if meta["kind"] != "enum" else f"{meta['stereotype']} · enum"
            lines.append(f"    class {name} {{")
            lines.append(f"        <<{label}>>")
            lines.append("    }")
        lines.append("")

    relations = []
    for name, meta in sorted(members.items()):
        body = meta["body"]

        for match in IMPLEMENTS.finditer(body):
            for target in supertype_names(match.group(1), known, name):
                relations.append(f"    {name} ..|> {target} : implements")
        for match in EXTENDS.finditer(body):
            for target in supertype_names(match.group(1), known, name):
                relations.append(f"    {name} --|> {target} : extends")

        holds = set()
        for match in FIELD.finditer(body):
            holds |= referenced_types(match.group(1), known, name)
        holds |= referenced_types(record_components(body, name), known, name)
        for target in sorted(holds):
            relations.append(f"    {name} --> {target}")

    lines.append("    %% ---- relations: implements, extends, and fields typed by another type ----")
    lines.extend(sorted(set(relations)))
    return "\n".join(lines)


# --------------------------------------------------------------------------- 3. ER diagram

CREATE_TABLE = re.compile(r"CREATE TABLE (\w+)\s*\((.*?)\n\);", re.DOTALL | re.IGNORECASE)


def sql_type_token(column_definition: str) -> str:
    head = column_definition.strip().split()
    if len(head) < 2:
        return "unknown"
    raw = head[1].upper().split("(")[0]
    return SQL_TYPE_TOKENS.get(raw, raw.lower())


def er_diagram() -> str:
    tables, relations = {}, []
    for migration in sorted(MIGRATIONS.glob("V*.sql")):
        sql = migration.read_text(encoding="utf-8")
        for table, block in CREATE_TABLE.findall(sql):
            columns = []
            for line in block.splitlines():
                line = line.strip().rstrip(",")
                if not line or line.upper().startswith("CONSTRAINT"):
                    # A single-column UNIQUE is a property of that column and is marked on it. A
                    # composite one is a property of the pair, and Mermaid has no notation for that —
                    # marking each column UK would claim each is independently unique, which is false.
                    # Composite constraints are described in the prose beside the diagram instead.
                    inner = re.search(r"UNIQUE\s*\(([^)]*)\)", line, re.IGNORECASE)
                    if inner and "," not in inner.group(1):
                        columns.append((inner.group(1).strip(), None, "UK"))
                    continue
                name = line.split()[0]
                if name.upper() in ("PRIMARY", "FOREIGN", "UNIQUE", "CHECK"):
                    continue
                key = "PK" if "PRIMARY KEY" in line.upper() else ("UK" if " UNIQUE" in line.upper() else "")
                columns.append((name, sql_type_token(line), key))

                reference = re.search(r"REFERENCES\s+(\w+)", line, re.IGNORECASE)
                if reference:
                    optional = "NOT NULL" not in line.upper()
                    cardinality = "||--o{" if not optional else "|o--o{"
                    relations.append(f"    {reference.group(1)} {cardinality} {table} : \"\"")

            merged = {}
            for name, type_token, key in columns:
                if name in merged:
                    existing_type, existing_key = merged[name]
                    merged[name] = (existing_type or type_token, existing_key or key)
                else:
                    merged[name] = (type_token, key)
            tables[table] = merged

    lines = ["erDiagram"]
    lines.extend(sorted(set(relations)))
    lines.append("")
    for table, columns in tables.items():
        lines.append(f"    {table} {{")
        for name, (type_token, key) in columns.items():
            lines.append(f"        {type_token or 'unknown'} {name}{(' ' + key) if key else ''}")
        lines.append("    }")
    return "\n".join(lines)


# --------------------------------------------------------------------------- rendering

def replace_region(text: str, marker: str, body: str) -> str:
    """Replaces the content between a pair of markers, which may be empty on a first run."""
    open_marker = f"<!-- generated:{marker} -->"
    close_marker = f"<!-- /generated:{marker} -->"
    pattern = re.compile(
        "%s.*?%s" % (re.escape(open_marker), re.escape(close_marker)), re.DOTALL,
    )
    if not pattern.search(text):
        raise SystemExit(f"marker {open_marker} not found in {README}")
    replacement = f"{open_marker}\n```mermaid\n{body}\n```\n{close_marker}"
    return pattern.sub(lambda _: replacement, text)


def render(existing: str, types: dict) -> str:
    updated = replace_region(existing, "context-map", context_map())
    updated = replace_region(updated, "class-diagram", class_diagram(types, CLASS_DIAGRAM_CONTEXT))
    return replace_region(updated, "er-diagram", er_diagram())


def main() -> int:
    check_only = "--check" in sys.argv
    existing = README.read_text(encoding="utf-8")
    updated = render(existing, discover_types())

    if updated == existing:
        print("diagrams are up to date")
        return 0
    if check_only:
        print("ERROR: the generated diagrams in README.md no longer match the source they describe.")
        print("Run: python docs/architecture/arch-diagrams/generate.py")
        return 1
    README.write_text(updated, encoding="utf-8")
    print(f"regenerated the diagrams in {README.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
