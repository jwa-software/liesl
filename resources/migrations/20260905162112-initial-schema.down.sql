-- Reverse dependency order: referencing tables before referenced ones, so
-- the rollback works whether or not the connection has foreign keys enabled.
-- SQLite drops a table's indexes with it, so they need no statements here.

DROP TABLE IF EXISTS judgment;

--;;

DROP TABLE IF EXISTS link;

--;;

DROP TABLE IF EXISTS document;

--;;

DROP TABLE IF EXISTS fetch_state;

--;;

DROP TABLE IF EXISTS source;
