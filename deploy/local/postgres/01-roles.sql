-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- Local development only (compose.yaml mounts this into /docker-entrypoint-initdb.d; it runs once, on an empty
-- volume). Creates the grantees of db/migration/postgresql/R__grants.sql. They cannot log in: locally the app
-- connects as the owner. Run `docker compose down -v` once if your volume predates this file.
CREATE ROLE trailkeep_app NOLOGIN;
CREATE ROLE trailkeep_purge NOLOGIN;
