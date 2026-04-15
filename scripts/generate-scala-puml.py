#!/usr/bin/env python3
"""Generate a high-level PlantUML diagram from Scala source files."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Set, Tuple

PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)")
DECL_RE = re.compile(
    r"^\s*(?:final\s+|sealed\s+|abstract\s+|case\s+)*"
    r"(class|trait|object|enum)\s+([A-Za-z_][A-Za-z0-9_]*)"
    r"(?:[^\n]*?\sextends\s+([^\n{]+))?"
)
IMPORT_RE = re.compile(r"^\s*import\s+(chess(?:\.[A-Za-z_][A-Za-z0-9_]*)+)")
TYPE_TOKEN_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*")


@dataclass(frozen=True)
class Decl:
    kind: str
    fq_name: str


def alias_for(fq_name: str) -> str:
    return fq_name.replace(".", "_")


def display_kind(kind: str) -> str:
    if kind == "trait":
        return "interface"
    if kind == "enum":
        return "enum"
    return "class"


def parse_source(path: Path) -> Tuple[str, List[Tuple[str, str, str]], List[str]]:
    package_name = ""
    declarations: List[Tuple[str, str, str]] = []
    imports: List[str] = []

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        if not package_name:
            package_match = PACKAGE_RE.match(raw_line)
            if package_match:
                package_name = package_match.group(1)

        import_match = IMPORT_RE.match(raw_line)
        if import_match:
            imports.append(import_match.group(1))

        decl_match = DECL_RE.match(raw_line)
        if decl_match:
            kind = decl_match.group(1)
            name = decl_match.group(2)
            extends_expr = decl_match.group(3) or ""
            declarations.append((kind, name, extends_expr))

    return package_name, declarations, imports


def resolve_symbol(token: str, known_by_fq: Dict[str, Decl], known_by_simple: Dict[str, Set[str]]) -> str | None:
    clean = token.split("[")[0].split("(")[0].strip()
    if not clean:
        return None

    if clean in known_by_fq:
        return clean

    simple = clean.split(".")[-1]
    candidates = known_by_simple.get(simple, set())
    if len(candidates) == 1:
        return next(iter(candidates))

    return None


def generate_puml(source_dir: Path, output_file: Path) -> None:
    scala_files = sorted(source_dir.rglob("*.scala"))

    declaration_map: Dict[str, Decl] = {}
    raw_extends: List[Tuple[str, str]] = []
    raw_imports: List[Tuple[str, str]] = []

    for file_path in scala_files:
        package_name, decls, imports = parse_source(file_path)
        for kind, name, extends_expr in decls:
            if not package_name:
                continue

            fq_name = f"{package_name}.{name}"
            if fq_name not in declaration_map:
                declaration_map[fq_name] = Decl(kind=kind, fq_name=fq_name)

            for token in TYPE_TOKEN_RE.findall(extends_expr):
                raw_extends.append((fq_name, token))

        owner = decls[0][1] if decls else ""
        if package_name and owner:
            owner_fq = f"{package_name}.{owner}"
            for imported in imports:
                raw_imports.append((owner_fq, imported))

    declarations = sorted(declaration_map.values(), key=lambda d: d.fq_name)
    known_by_fq: Dict[str, Decl] = {decl.fq_name: decl for decl in declarations}
    known_by_simple: Dict[str, Set[str]] = {}

    for decl in declarations:
        simple = decl.fq_name.split(".")[-1]
        known_by_simple.setdefault(simple, set()).add(decl.fq_name)

    inheritance_edges: Set[Tuple[str, str]] = set()
    dependency_edges: Set[Tuple[str, str]] = set()

    for source, target_token in raw_extends:
        target = resolve_symbol(target_token, known_by_fq, known_by_simple)
        if target and target != source:
            inheritance_edges.add((source, target))

    for source, imported in raw_imports:
        target = resolve_symbol(imported, known_by_fq, known_by_simple)
        if target and target != source:
            dependency_edges.add((source, target))

    output_file.parent.mkdir(parents=True, exist_ok=True)

    lines: List[str] = [
        "@startuml",
        "skinparam shadowing false",
        "hide empty members",
        "",
        "' Auto-generated from src/main/scala by scripts/generate-scala-puml.py",
    ]

    for decl in declarations:
        alias = alias_for(decl.fq_name)
        kind = display_kind(decl.kind)
        lines.append(f'{kind} "{decl.fq_name}" as {alias}')

    lines.append("")

    for source, target in sorted(inheritance_edges):
        lines.append(f"{alias_for(source)} --|> {alias_for(target)}")

    for source, target in sorted(dependency_edges):
        lines.append(f"{alias_for(source)} ..> {alias_for(target)}")

    lines.append("@enduml")
    lines.append("")

    output_file.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    repo_root = Path(__file__).resolve().parents[1]
    source_root = repo_root / "src" / "main" / "scala"
    target_file = repo_root / "diagrams" / "generated" / "scala-architecture.puml"

    generate_puml(source_root, target_file)
    print(f"generated: {target_file.relative_to(repo_root)}")

