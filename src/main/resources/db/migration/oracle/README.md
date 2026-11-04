<!-- SPDX-License-Identifier: Apache-2.0 -->
# Oracle-only migrations

Scripts that cannot be portable (for example `R__grants.sql`, whose sequence grants differ). Selected by
Spring Boot's `{vendor}` location when the DataSource is Oracle. The migration lint applies here too.
