-- The plan chosen at /subscribe is remembered here while the society trials on FREE_TRIAL limits;
-- the dunning job converts the trial to this plan when it ends.
ALTER TABLE subscriptions ADD COLUMN next_plan TEXT
    CHECK (next_plan IS NULL OR next_plan IN ('BASIC','STANDARD','PREMIUM'));

-- Season detail pages carry a share row (§13), so a share may now reference a season.
-- SQLite requires a NULL default when a REFERENCES column is added with foreign keys on.
ALTER TABLE social_shares ADD COLUMN season_id INTEGER REFERENCES seasons(id) ON DELETE SET NULL;
CREATE INDEX idx_shares_season ON social_shares(season_id);