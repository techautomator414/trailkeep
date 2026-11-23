# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Charlie Genova and contributors
#
# Oracle XE 21, the first image of the oracle-it matrix (the pom's oracle-xe-21 execution names it).
# The one place this image is named: TestImages reads the FROM line, and Dependabot's docker ecosystem
# proposes updates to the tag and the digest (issue 235). Keep exactly one FROM, pinned by digest.
# The full image, not -slim: the slim images remove Oracle Text, which the V21 search index needs (ADR-0017).
FROM gvenzl/oracle-xe:21@sha256:c2682a4216b0fe65537912c4a049c7e5c15a85bb7c94bb8137d5f2d2eef60603
