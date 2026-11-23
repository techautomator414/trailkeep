#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Charlie Genova and contributors
"""The release evidence pack (BUILD_PLAN §12.3; IMPLEMENTATION_PLAN WP-30 part d).

The pack is described by .github/release/evidence-pack.txt. Three commands, run by release.yml from the repository root:

  preflight  before anything is built or published: the checked-in files the release needs exist (from 1.0.0 none of
             them calls itself a draft), and CHANGELOG.md has a section for the version, written to --out.
  assemble   after the image is verified: builds the pack from the run's artifacts and the tagged checkout, writes
             VERIFY.md, EVIDENCE.md and SHA256SUMS. Fails if a required file cannot be produced.
  check      just before the GitHub Release: every required file is in the pack, nothing else is, and SHA256SUMS
             matches every file.

Standard library only; release.yml runs it with `python3 -I`. ReleaseEvidencePackTest runs it against fixtures.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

MANIFEST = Path(".github/release/evidence-pack.txt")
MIGRATIONS = Path("src/main/resources/db/migration")
TAG = re.compile(r"v(\d+)\.(\d+)\.(\d+)(?:[-+][0-9A-Za-z.+-]+)?")
ISSUER = "https://token.actions.githubusercontent.com"
UNSIGNED = {"SHA256SUMS", "SHA256SUMS.sigstore.json"}  # the checksums cannot list themselves or their own signature
ZIP_TIME = (1980, 1, 1, 0, 0, 0)  # fixed, so the same inputs give the same zip


@dataclass(frozen=True)
class Row:
    file: str
    source: str
    since: str
    requirement: str
    verify: str

    def required(self, version: tuple[int, int, int]) -> bool:
        return self.since == "all" or version >= parse_version("v" + self.since)[0]


def parse_version(tag: str) -> tuple[tuple[int, int, int], str]:
    match = TAG.fullmatch(tag)
    if not match:
        sys.exit(f"not a release tag: {tag}")
    return (int(match[1]), int(match[2]), int(match[3])), tag[1:]


def read_manifest(path: Path) -> list[Row]:
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        fields = [field.strip() for field in line.split(" | ")]
        if len(fields) != 5:
            sys.exit(f"{path}:{number}: expected 5 fields, found {len(fields)}")
        row = Row(*fields)
        if row.since != "all":
            parse_version("v" + row.since)
        kind, _, name = row.source.partition(":")
        if kind == "artifact" and "/" not in name and row.file != name + ".zip":
            sys.exit(f"{path}:{number}: a whole artifact is packed as {name}.zip, not {row.file}")
        if (row.file == "-") != (kind == "registry"):
            sys.exit(f"{path}:{number}: only registry evidence has no file")
        rows.append(row)
    return rows


def fail_if(errors: list[str]) -> None:
    if errors:
        for error in errors:
            print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)


# ---- preflight -------------------------------------------------------------------------------------------------------


def changelog_section(changelog: str, version: str) -> str | None:
    match = re.search(rf"(?m)^## \[{re.escape(version)}\][^\n]*\n(.*?)(?=^## \[|\Z)", changelog, re.S)
    if not match or not match[1].strip():
        return None
    return match[0].rstrip() + "\n"


def preflight(args: argparse.Namespace) -> None:
    version, plain = parse_version(args.tag)
    errors = []
    for row in read_manifest(args.manifest):
        kind, _, path = row.source.partition(":")
        if kind != "repo" or not row.required(version):
            continue
        source = Path(path)
        if not source.exists():
            errors.append(f"{path} is required in the {args.tag} evidence pack ({row.requirement}) and is missing")
        elif row.since != "all" and source.is_file() and is_draft(source):
            errors.append(f"{path} calls itself a draft in its title; {args.tag} needs the final {row.requirement}")
    section = changelog_section(Path("CHANGELOG.md").read_text(encoding="utf-8"), plain)
    if section is None:
        errors.append(f"CHANGELOG.md has no section '## [{plain}]' with entries")
    fail_if(errors)
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "CHANGELOG-section.md").write_text(section or "", encoding="utf-8")
    print(f"preflight passed for {args.tag}")


def is_draft(path: Path) -> bool:
    title = next((line for line in path.read_text(encoding="utf-8").splitlines() if line.startswith("# ")), "")
    return "DRAFT" in title.upper()


# ---- assemble --------------------------------------------------------------------------------------------------------


def zip_tree(source: Path, prefix: str, target: Path) -> None:
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                info = zipfile.ZipInfo(f"{prefix}/{path.relative_to(source).as_posix()}", ZIP_TIME)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                archive.writestr(info, path.read_bytes())


def accessibility(artifacts: Path) -> str:
    """A table of the accessibility ITs (axe with 0 violations, and the Nu HTML check) from the failsafe reports."""
    rows, executed, failed = [], 0, 0
    for report in sorted((artifacts / "test-reports").rglob("TEST-*AccessibilityIT.xml")):
        # Only the attributes of the root <testsuite> element are read, by pattern rather than with an XML parser, so
        # nothing in the report (entities, DTDs) is ever interpreted.
        tag = re.search(r"<testsuite\b[^>]*>", report.read_text(encoding="utf-8", errors="replace"))
        suite = dict(re.findall(r'\s([\w:-]+)="([^"]*)"', tag[0])) if tag else {}
        counts = [int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")]
        executed += counts[0] - counts[3]
        failed += counts[1] + counts[2] + (0 if tag else 1)
        rows.append(f"| `{suite.get('name', report.stem)}` | " + " | ".join(map(str, counts)) + " |")
    if not rows or executed == 0 or failed:
        raise ValueError(f"{len(rows)} accessibility IT reports, {executed} tests run, {failed} failed")
    return (
        "# Accessibility test results\n\n"
        "Every screen's accessibility IT renders it in Chromium, runs axe (the WCAG 2.0, 2.1 and 2.2 A and AA rules)\n"
        "and asserts 0 violations, and validates the HTML with the Nu HTML Checker (BUILD_PLAN §10.2). Counts from the\n"
        "release run's failsafe reports (test-reports.zip). The manual conformance evidence is the ACR (accessibility/).\n\n"
        "| Test class | Tests | Failures | Errors | Skipped |\n|---|---:|---:|---:|---:|\n"
        + "\n".join(rows)
        + f"\n\n{len(rows)} classes, {executed} tests run, 0 failed.\n"
    )


def flyway() -> str:
    """The highest versioned migration of each folder, then every versioned file with its SHA-256."""
    def key(path: Path) -> tuple[str, list[int]]:
        return path.parent.name, [int(part) for part in re.split(r"[._]", path.name[1:].split("__")[0])]

    files = sorted(MIGRATIONS.glob("*/V*__*.sql"), key=key)
    latest: dict[str, str] = {}
    for path in files:
        latest[path.parent.name] = path.name.split("__")[0]
    lines = ["Flyway schema version (highest versioned migration per folder; a database migrated by this release is at"]
    lines.append("the highest of common and its vendor folder):")
    lines += [f"  {folder}: {version}" for folder, version in sorted(latest.items())]
    lines += ["", "Versioned migrations (SHA-256):"]
    lines += [f"{sha256(path)}  {path.as_posix()}" for path in files]
    return "\n".join(lines) + "\n"


def verify_md(tag: str, image: str, digest: str, repository: str) -> str:
    identity = f"https://github.com/{repository}/.github/workflows/release.yml@refs/tags/{tag}"
    flags = f"--certificate-identity '{identity}' \\\n  --certificate-oidc-issuer {ISSUER}"
    return f"""# Verifying Trailkeep {tag}

Image: `{image}@{digest}`. Every signature below is keyless (Sigstore), made by `release.yml` running for this tag,
so each check names that exact identity. Use cosign v3 or later for the bundles.

## 1. The evidence pack

```
sha256sum -c SHA256SUMS
cosign verify-blob --bundle SHA256SUMS.sigstore.json {flags} \\
  SHA256SUMS
```

## 2. The image signature and the SBOM

```
cosign verify {image}@{digest} \\
  {flags}
cosign verify-attestation --type cyclonedx {image}@{digest} \\
  {flags}
cosign verify-blob --bundle bom.json.sigstore.json {flags} \\
  bom.json
```

## 3. The SLSA provenance

slsa-github-generator writes the earlier cosign format, which cosign v3 does not read; use slsa-verifier:

```
slsa-verifier verify-image {image}@{digest} \\
  --source-uri github.com/{repository} --source-tag {tag}
```

## 4. The tag

`tag-signature.txt` records the tag object, the commit it points to and GitHub's verdict on its signature. To check
the signature yourself, with the maintainer's public signing key (see docs/RELEASING.md):

```
git fetch origin tag {tag}
git tag -v {tag}
gh api repos/{repository}/git/ref/tags/{tag} --jq .object.sha   # the tag object
gh api repos/{repository}/git/tags/<tag object sha> --jq .verification
```
"""


def cell(text: str) -> str:
    return text.replace("|", "\\|")


def last_change(path: str) -> str:
    out = subprocess.run(
        ["git", "log", "-1", "--format=%h %cs", "--", path], capture_output=True, text=True, check=False
    ).stdout.strip()
    return f" (last changed {out})" if out else ""


def assemble(args: argparse.Namespace) -> None:
    version, _ = parse_version(args.tag)
    rows = read_manifest(args.manifest)
    pack: Path = args.pack
    if pack.exists() and any(pack.iterdir()):
        sys.exit(f"{pack} is not empty")
    pack.mkdir(parents=True, exist_ok=True)
    errors, present = [], set()
    for row in rows:
        kind, _, name = row.source.partition(":")
        target = pack / row.file
        if kind == "artifact" or kind == "repo":
            source = args.artifacts / name if kind == "artifact" else Path(name)
            if source.is_dir():
                zip_tree(source, source.name, target)
            elif source.is_file():
                shutil.copyfile(source, target)
        elif row.source == "generated:accessibility":
            try:
                target.write_text(accessibility(args.artifacts), encoding="utf-8")
            except ValueError as problem:
                errors.append(f"{row.file}: {problem}")
                continue
        elif row.source == "generated:flyway":
            target.write_text(flyway(), encoding="utf-8")
        elif row.source == "generated:verify":
            target.write_text(verify_md(args.tag, args.image, args.digest, args.repository), encoding="utf-8")
        if target.is_file():
            present.add(row.file)
        elif kind in ("artifact", "repo", "generated") and row.source not in ("generated:index", "generated:sums"):
            if row.required(version):
                errors.append(f"{row.file} ({row.requirement}) could not be produced from {row.source}")
    fail_if(errors)
    (pack / "EVIDENCE.md").write_text(index(args, rows, present, version), encoding="utf-8")
    sums = [f"{sha256(path)}  {path.name}" for path in sorted(pack.iterdir()) if path.name not in UNSIGNED]
    (pack / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="utf-8")
    print(f"assembled {len(sums) + 1} files in {pack}")


def index(args: argparse.Namespace, rows: list[Row], present: set[str], version: tuple[int, int, int]) -> str:
    commit = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=False).stdout.strip()
    lines = [
        f"# Trailkeep {args.tag}: release evidence",
        "",
        f"- Image: `{args.image}@{args.digest}`",
        f"- Commit: `{commit or 'unknown'}`",
        f"- Release run: {args.run_url}",
        "- What the pack must contain: `.github/release/evidence-pack.txt` at this tag (BUILD_PLAN §12.3, WP-30).",
        "- Every file here is listed in `SHA256SUMS`, which `SHA256SUMS.sigstore.json` signs; `VERIFY.md` has the commands.",
        "- Files from the repository are the reviewed, checked-in copies at this commit, never written at release time.",
        "",
        "| Requirement | File | Source | How to verify |",
        "|---|---|---|---|",
    ]
    for row in rows:
        kind, _, name = row.source.partition(":")
        if row.file == "-":
            file = "(in the registry, beside the image)"
        elif row.file in present or kind == "workflow" or row.source in ("generated:index", "generated:sums"):
            file = f"`{row.file}`"
        else:
            file = f"not in this release (required from {row.since})"
        source = {
            "artifact": f"release run, artifact `{name}`",
            "repo": f"`{name}` at this commit{last_change(name)}",
            "generated": "written by evidence-pack.py from this run",
            "workflow": "release.yml, after the pack is assembled",
            "registry": "release.yml (publish, provenance); checked by verify-image.yml",
        }[kind]
        lines.append(f"| {cell(row.requirement)} | {file} | {cell(source)} | {cell(row.verify)} |")
    return "\n".join(lines) + "\n"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


# ---- check -----------------------------------------------------------------------------------------------------------


def check(args: argparse.Namespace) -> None:
    version, _ = parse_version(args.tag)
    rows = read_manifest(args.manifest)
    pack: Path = args.pack
    files = {path.name for path in pack.iterdir()}
    known = {row.file for row in rows}
    errors = [f"{row.file} ({row.requirement}) is missing from the pack" for row in rows
              if row.file != "-" and row.required(version) and row.file not in files]
    errors += [f"{name} is in the pack but not in {args.manifest}" for name in sorted(files - known)]
    if "SHA256SUMS" in files:
        listed = {}
        for line in (pack / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
            digest, _, name = line.partition("  ")
            listed[name] = digest
        for name in sorted(files - UNSIGNED):
            if listed.get(name) != sha256(pack / name):
                errors.append(f"SHA256SUMS does not match {name}")
        errors += [f"SHA256SUMS lists {name}, which is not in the pack" for name in sorted(set(listed) - files)]
    fail_if(errors)
    print(f"the {args.tag} evidence pack is complete: {len(files)} files")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    commands = parser.add_subparsers(dest="command", required=True)
    first = commands.add_parser("preflight")
    first.add_argument("--tag", required=True)
    first.add_argument("--out", type=Path, required=True)
    build = commands.add_parser("assemble")
    for flag in ("--tag", "--image", "--digest", "--repository", "--run-url"):
        build.add_argument(flag, required=True)
    build.add_argument("--artifacts", type=Path, required=True)
    build.add_argument("--pack", type=Path, required=True)
    last = commands.add_parser("check")
    last.add_argument("--tag", required=True)
    last.add_argument("--pack", type=Path, required=True)
    args = parser.parse_args()
    {"preflight": preflight, "assemble": assemble, "check": check}[args.command](args)


if __name__ == "__main__":
    main()
