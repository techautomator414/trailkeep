<!-- SPDX-License-Identifier: Apache-2.0 -->
# Releasing Trailkeep

How a version is released, end to end, with 1.0.0 in mind (IMPLEMENTATION_PLAN WP-30: "release workflow + evidence
pack, CHANGELOG, signed tag"). Pushing a `v*` tag is the only trigger; everything after it is
`.github/workflows/release.yml`. Steps marked **owner** need the repository owner: a maintainer's signing key, a
repository secret, or the tag itself. Everything else goes through ordinary pull requests.

## 1. What the release run does

| Job | What it does | A failure means |
|---|---|---|
| `preflight` | Checks the tag (it must point at the commit being built; from 1.0.0 it must be an annotated tag whose signature GitHub verifies), that `SONAR_TOKEN` and `NVD_API_KEY` are set (from 1.0.0), that every checked-in file the evidence pack needs exists (from 1.0.0, none of them titled DRAFT), and cuts the version's CHANGELOG section | Nothing was built or published. Fix and re-tag (section 5) |
| `main-gates` | `main.yml`: SonarCloud, Dependency-Check, SBOM + Trivy, image + Trivy + Dockle + kube-linter, the Oracle matrix, the SQL Server ITs | Nothing was published |
| `verify` | `./mvnw verify` cold (no cache): every unit, PostgreSQL and browser test, axe and the Nu checker, every static gate | Nothing was published |
| `zap` | `zap.yml` in release mode: any Medium or higher alert, or a partial scan, fails | Nothing was published |
| `perf` | `perf.yml`: the 50k and 500k seeds on PostgreSQL in process; a p95 past its target fails | Nothing was published |
| `publish` | Builds the image cold from digest-pinned bases, scans it, pushes it to GHCR, signs it and attests the SBOM (keyless cosign) | Nothing was published, or an image without a release (section 5) |
| `provenance` | SLSA provenance (slsa-github-generator) attached to the image | An image without a release |
| `verify-published` | `verify-image.yml`: the signature, the SBOM attestation and the provenance, checked as a user would, against `release.yml@refs/tags/<tag>` | An image without a release |
| `github-release` | Assembles the evidence pack, signs its `SHA256SUMS`, checks the pack is complete, creates the GitHub Release | An image without a release |

## 2. The evidence pack

`.github/release/evidence-pack.txt` is the list: every file a release carries, where it comes from, and the first
version that requires it. `.github/scripts/evidence-pack.py` builds the pack from it, and the release fails if a
required file cannot be produced, if the pack holds a file the list does not name, or if a checksum does not match.
`ReleaseEvidencePackTest` keeps the list and `release.yml` in step, and `EvidencePackScriptTest` runs the script.

Every file is one of three kinds, and none is written by hand at release time:

- **Produced by the release run**: test, coverage, integration, scan, DAST and performance reports, the SBOM and its
  signature, the image digest, the licence report (`THIRD-PARTY.txt`), the accessibility test table, the Flyway
  schema version.
- **Reviewed, checked-in files at the tagged commit**: the k6 and Oracle performance runs (`docs/perf/`), the restore
  drill (`docs/ops/restore-drill.md`), the runbook, the security test map, threat model and data map, the suppression
  files, the ACR. `EVIDENCE.md` gives each one's last commit and date, so a reader sees how old it is.
- **In the registry beside the image**: the cosign signature, the SBOM attestation and the SLSA provenance.

The release attaches `EVIDENCE.md` (each requirement, its file and how to verify it), `VERIFY.md` (every verification
command, with this tag and digest filled in), `SHA256SUMS` and `SHA256SUMS.sigstore.json` (a keyless signature over the
checksums by `release.yml` at the tag, so one `cosign verify-blob` covers the whole pack).

### Why the k6 runs are checked in and not run by the release

k6 needs the application behind a real identity provider with redirects, and its load and pool-wait numbers are only
meaningful on a known, quiet machine; a shared runner gives noise, not a gate (`docs/perf/release-500k.md`, "What CI
runs"). So the release runs what can run on a runner without an identity provider, the in-process measurement at the
50k and 500k seeds (`perf.yml`, the same job as nightly, which gates the release on the plan's targets), and attaches
the k6 and Oracle 500k results recorded by hand in `docs/perf/release-500k.md`. IMPLEMENTATION_PLAN Part G lists "k6
performance at 50k/500k seed" in the nightly and release columns; this is the deviation, recorded here and in the pull
request that made it.

## 3. Before tagging 1.0.0

1. **WP-29 is merged**: the ACR (VPAT 2.5) with the manual test matrix is at `accessibility/ACR.md`, its title does not
   say DRAFT, it has no "Does Not Support", and each "Partially Supports" has a dated ticket. If WP-29 names the file
   differently, change the `ACR.md` row of `.github/release/evidence-pack.txt` in the same pull request.
2. **Owner: the secret-gated scans run** (issue 30): `SONAR_TOKEN` and `NVD_API_KEY` are repository secrets, and the last
   `main` run shows SonarCloud and Dependency-Check green, not skipped. From 1.0.0 the preflight refuses a release
   without them, and the pack requires `dependency-check-report.zip`.
3. **Fresh hand-run evidence**: on the release candidate commit, re-run the k6 mix and the Oracle 500k run
   (`docs/perf/release-500k.md`) and, if the schema or the attachment path changed since, the restore drill
   (`docs/ops/restore-drill.md`); record the dated results in those pages by pull request.
4. **A green nightly on the candidate commit** (ZAP in nightly mode, perf, the Oracle matrix), so the release run holds
   no surprises.
5. **The release pull request** (release manager): move the `[Unreleased]` entries of `CHANGELOG.md` under
   `## [1.0.0] - <date>`, leave an empty `[Unreleased]` above it, and merge. The preflight refuses a version with no
   section.

## 4. The signed tag (owner)

WP-30's "signed tag" is a signed git tag. The image is signed separately by the release run (keyless cosign), which
needs no key from anyone. From 1.0.0 `preflight` asks GitHub's API for the tag's verification
(`GET /repos/{owner}/{repo}/git/tags/{sha}`) and refuses the release unless it is `verified: true`, which GitHub
reports only for a signature by a signing key on the tagger's account, with the tagger's email verified on that account.
Earlier tags have their state recorded in `tag-signature.txt` and are not refused.

**Once: a signing key.** An SSH key is simplest (a GPG key works the same way with `gpg.format openpgp`):

    ssh-keygen -t ed25519 -C "<your verified GitHub email>" -f ~/.ssh/trailkeep_signing
    git config --global gpg.format ssh
    git config --global user.signingkey ~/.ssh/trailkeep_signing.pub
    # To verify SSH signatures locally (git tag -v):
    echo "<your verified GitHub email> namespaces=\"git\" $(cat ~/.ssh/trailkeep_signing.pub)" >> ~/.ssh/allowed_signers
    git config --global gpg.ssh.allowedSignersFile ~/.ssh/allowed_signers

Then add the public key on GitHub under Settings, SSH and GPG keys, New SSH key, with **Key type: Signing Key** (an
authentication key does not count), and check `git config user.email` is a verified email on the account.

**Each release:**

    git fetch origin && git switch main && git pull --ff-only
    git log -1 --format='%H %s'          # the release pull request's merge commit
    git tag -s v1.0.0 -m "Trailkeep 1.0.0"
    git tag -v v1.0.0                    # "Good \"git\" signature for <email>"
    git push origin v1.0.0               # starts release.yml

Then check what GitHub sees, which is what the preflight checks:

    sha=$(gh api repos/techautomator414/trailkeep/git/ref/tags/v1.0.0 --jq .object.sha)
    gh api repos/techautomator414/trailkeep/git/tags/$sha --jq .verification

Optionally, publish the signing key's public half in the repository (an `allowed_signers` file) so users can run
`git tag -v` without trusting GitHub; that is a separate, reviewed pull request. A tag ruleset that lets only the owner
create `v*` tags is a repository setting and also the owner's choice.

## 5. When the run fails

- **In `preflight` or a gate**: nothing was published. Fix on `main` by pull request, then move the tag:
  `git push origin :refs/tags/v1.0.0`, `git tag -d v1.0.0`, tag again (section 4), push.
- **After `publish`** (provenance, verification or the GitHub Release): the image is in GHCR under the version, signed,
  but there is no release. Re-run the failed jobs if the cause was transient (a registry or Sigstore outage). Otherwise
  never re-tag a version whose image is published: fix by pull request and release the next patch version.

## 6. After the release

Run the commands in the release's `VERIFY.md` (also in its notes), check `EVIDENCE.md` lists every requirement with a
file, and that `sha256sum -c SHA256SUMS` passes on the downloaded assets. `verify-image.yml` can be dispatched by hand
against the tag and digest at any time. Then the release manager refreshes `HANDOFF.md` and the README status.
