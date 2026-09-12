PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS players (
  uuid           TEXT PRIMARY KEY,
  username       TEXT NOT NULL,
  username_lower TEXT NOT NULL,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_players_username_lower ON players(username_lower);

CREATE TABLE IF NOT EXISTS auth_challenges (
  server_id  TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_challenges_expires ON auth_challenges(expires_at);

CREATE TABLE IF NOT EXISTS servers (
  server_id  TEXT PRIMARY KEY,
  owner_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_servers_owner ON servers(owner_uuid);

CREATE TABLE IF NOT EXISTS presence (
  server_id       TEXT PRIMARY KEY REFERENCES servers(server_id) ON DELETE CASCADE,
  tunnel_hostname TEXT NOT NULL,
  mc_version      TEXT,
  last_seen       INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_presence_last_seen ON presence(last_seen);

CREATE TABLE IF NOT EXISTS friends (
  a_uuid     TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
  b_uuid     TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (a_uuid, b_uuid),
  CHECK (a_uuid < b_uuid)
);

CREATE INDEX IF NOT EXISTS idx_friends_b ON friends(b_uuid);

CREATE TABLE IF NOT EXISTS friend_requests (
  from_uuid  TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
  to_uuid    TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (from_uuid, to_uuid)
);
CREATE INDEX IF NOT EXISTS idx_friend_requests_to ON friend_requests(to_uuid);
