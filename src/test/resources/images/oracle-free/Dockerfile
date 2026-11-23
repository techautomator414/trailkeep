# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Charlie Genova and contributors
#
# Oracle Free 23, the second image of the oracle-it matrix and the default of OracleTestcontainersConfiguration.
# The one place this image is named: TestImages reads the FROM line, and Dependabot's docker ecosystem
# proposes updates to the tag and the digest (issue 235). Keep exactly one FROM, pinned by digest.
# The full image, not -slim: the slim images remove Oracle Text, which the V21 search index needs (ADR-0006,
# amended 2026-11-23).
FROM gvenzl/oracle-free:23@sha256:188ff1612d2abe1f33a8999aa9a5495a372031fc71b68fca8f375315f28e065d
