-- =====================================================================
-- SCPL V1 baseline schema (SQLite dialect)
-- Conventions: INTEGER PRIMARY KEY AUTOINCREMENT ids, enums as TEXT,
-- booleans as INTEGER 0/1, dates as ISO-8601 TEXT, money as REAL.
-- Every FK column is indexed. Dependent data CASCADEs; optional refs SET NULL.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Tenants
-- ---------------------------------------------------------------------
CREATE TABLE societies (
                           id             INTEGER PRIMARY KEY AUTOINCREMENT,
                           name           TEXT    NOT NULL,
                           slug           TEXT    NOT NULL,
                           description    TEXT,
                           city           TEXT,
                           address        TEXT,
                           logo_url       TEXT,
                           contact_email  TEXT,
                           contact_phone  TEXT,
                           status         TEXT    NOT NULL DEFAULT 'DRAFT'
                               CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED')),
                           admin_count    INTEGER NOT NULL DEFAULT 0,
                           created_at     TEXT    NOT NULL
);
CREATE UNIQUE INDEX uq_societies_slug ON societies(slug);
CREATE INDEX idx_societies_status     ON societies(status);
CREATE INDEX idx_societies_created_at ON societies(created_at);

CREATE TABLE seasons (
                         id               INTEGER PRIMARY KEY AUTOINCREMENT,
                         society_id       INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                         name             TEXT    NOT NULL,
                         year             INTEGER,
                         status           TEXT    NOT NULL DEFAULT 'UPCOMING',
                         start_date       TEXT,
                         end_date         TEXT,
                         registration_open INTEGER NOT NULL DEFAULT 0 CHECK (registration_open IN (0,1)),
                         overs_per_match  INTEGER NOT NULL DEFAULT 10,
                         team_purse       REAL    NOT NULL DEFAULT 0,
                         planned_budget   REAL    NOT NULL DEFAULT 0,
                         description      TEXT,
                         created_at       TEXT    NOT NULL
);
CREATE INDEX idx_seasons_society    ON seasons(society_id);
CREATE INDEX idx_seasons_status     ON seasons(status);
CREATE INDEX idx_seasons_created_at ON seasons(created_at);

CREATE TABLE teams (
                       id                INTEGER PRIMARY KEY AUTOINCREMENT,
                       society_id        INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                       season_id         INTEGER NOT NULL REFERENCES seasons(id)   ON DELETE CASCADE,
                       name              TEXT    NOT NULL,
                       short_name        TEXT,
                       color             TEXT,
                       logo_url          TEXT,
                       captain_player_id INTEGER REFERENCES players(id) ON DELETE SET NULL,
                       purse_remaining   REAL    NOT NULL DEFAULT 0,
                       created_at        TEXT    NOT NULL
);
CREATE INDEX idx_teams_society ON teams(society_id);
CREATE INDEX idx_teams_season  ON teams(season_id);
CREATE INDEX idx_teams_captain ON teams(captain_player_id);

CREATE TABLE players (
                         id            INTEGER PRIMARY KEY AUTOINCREMENT,
                         society_id    INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                         team_id       INTEGER REFERENCES teams(id) ON DELETE SET NULL,
                         name          TEXT    NOT NULL,
                         email         TEXT,
                         phone         TEXT,
                         date_of_birth TEXT,
                         address       TEXT,
                         flat_number   TEXT,
                         role          TEXT,
                         batting_style TEXT,
                         bowling_style TEXT,
                         jersey_number INTEGER,
                         base_price    REAL    NOT NULL DEFAULT 0,
                         photo_url     TEXT,
                         matches_played INTEGER NOT NULL DEFAULT 0,
                         runs          INTEGER NOT NULL DEFAULT 0,
                         wickets       INTEGER NOT NULL DEFAULT 0,
                         active        INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
                         created_at    TEXT    NOT NULL
);
CREATE INDEX idx_players_society    ON players(society_id);
CREATE INDEX idx_players_team       ON players(team_id);
CREATE INDEX idx_players_created_at ON players(created_at);
-- Backs the "email_exists" conflict check on import; NULL emails are allowed to repeat.
CREATE UNIQUE INDEX uq_players_society_email ON players(society_id, email) WHERE email IS NOT NULL;

-- ---------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------
CREATE TABLE users (
                       id            INTEGER PRIMARY KEY AUTOINCREMENT,
                       username      TEXT    NOT NULL,
                       password_hash TEXT    NOT NULL,
                       email         TEXT    NOT NULL,
                       full_name     TEXT,
                       phone         TEXT,
                       role          TEXT    NOT NULL
                           CHECK (role IN ('PLATFORM_ADMIN','SOCIETY_ADMIN','SOCIETY_PLAYER')),
                       society_id    INTEGER REFERENCES societies(id) ON DELETE CASCADE,
                       player_id     INTEGER REFERENCES players(id)   ON DELETE SET NULL,
                       enabled       INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1)),
                       created_at    TEXT    NOT NULL,
                       last_login_at TEXT
);
CREATE UNIQUE INDEX uq_users_username ON users(username COLLATE NOCASE);
CREATE UNIQUE INDEX uq_users_email    ON users(email COLLATE NOCASE);
CREATE UNIQUE INDEX uq_users_player   ON users(player_id) WHERE player_id IS NOT NULL;
CREATE INDEX idx_users_society    ON users(society_id);
CREATE INDEX idx_users_role       ON users(role);
CREATE INDEX idx_users_created_at ON users(created_at);

-- Admin team contact cards (Contacts tab). user_id is optional: a contact may be
-- a committee member who has no login.
CREATE TABLE admin_contacts (
                                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                                society_id      INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                                user_id         INTEGER REFERENCES users(id) ON DELETE SET NULL,
                                name            TEXT    NOT NULL,
                                role_title      TEXT,
                                phone           TEXT,
                                whatsapp_number TEXT,
                                email           TEXT,
                                created_at      TEXT    NOT NULL
);
CREATE INDEX idx_admin_contacts_society ON admin_contacts(society_id);
CREATE INDEX idx_admin_contacts_user    ON admin_contacts(user_id);

-- ---------------------------------------------------------------------
-- 3-of-N approvals
-- ---------------------------------------------------------------------
CREATE TABLE admin_approvals (
                                 id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                                 society_id         INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                                 entity_type        TEXT    NOT NULL
                                     CHECK (entity_type IN ('REGISTRATION','REGISTRATION_BATCH','PLAYER_DROP',
                                                            'BULK_IMPORT','SEASON_DELETE','ADMIN_INVITE',
                                                            'ADMIN_REMOVE','TOURNAMENT_JOIN')),
                                 entity_id          INTEGER,
                                 requested_by       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                 requested_at       TEXT    NOT NULL,
                                 status             TEXT    NOT NULL DEFAULT 'PENDING'
                                     CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
                                 approvals_required INTEGER NOT NULL DEFAULT 3,
                                 eligible_voters    INTEGER NOT NULL DEFAULT 3,   -- snapshot of society.admin_count at request time
                                 notes              TEXT,
                                 payload            TEXT,                          -- JSON context for performApprovedAction
                                 decided_at         TEXT,
                                 decision_notes     TEXT
);
CREATE INDEX idx_approvals_society      ON admin_approvals(society_id);
CREATE INDEX idx_approvals_requested_by ON admin_approvals(requested_by);
CREATE INDEX idx_approvals_status       ON admin_approvals(status);
CREATE INDEX idx_approvals_entity       ON admin_approvals(entity_type, entity_id);
-- Partial index: the voting queue only ever scans open items, so index only those rows.
CREATE INDEX idx_approvals_pending ON admin_approvals(society_id, requested_at) WHERE status = 'PENDING';

-- A REGISTRATION_BATCH approval wraps many ids; one row per wrapped entity.
CREATE TABLE admin_approval_items (
                                      id          INTEGER PRIMARY KEY AUTOINCREMENT,
                                      approval_id INTEGER NOT NULL REFERENCES admin_approvals(id) ON DELETE CASCADE,
                                      entity_id   INTEGER NOT NULL
);
CREATE INDEX idx_approval_items_approval ON admin_approval_items(approval_id);
CREATE UNIQUE INDEX uq_approval_items ON admin_approval_items(approval_id, entity_id);

CREATE TABLE admin_approval_votes (
                                      id          INTEGER PRIMARY KEY AUTOINCREMENT,
                                      approval_id INTEGER NOT NULL REFERENCES admin_approvals(id) ON DELETE CASCADE,
                                      voter_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                      vote        TEXT    NOT NULL CHECK (vote IN ('APPROVE','REJECT')),
                                      voted_at    TEXT    NOT NULL,
                                      comment     TEXT,
                                      CONSTRAINT uq_vote_once UNIQUE (approval_id, voter_id)   -- stops duplicate votes
);
CREATE INDEX idx_votes_approval ON admin_approval_votes(approval_id);
CREATE INDEX idx_votes_voter    ON admin_approval_votes(voter_id);

-- ---------------------------------------------------------------------
-- League operations
-- ---------------------------------------------------------------------
CREATE TABLE registrations (
                               id            INTEGER PRIMARY KEY AUTOINCREMENT,
                               society_id    INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                               season_id     INTEGER NOT NULL REFERENCES seasons(id)   ON DELETE CASCADE,
                               full_name     TEXT    NOT NULL,
                               email         TEXT    NOT NULL,
                               phone         TEXT,
                               date_of_birth TEXT,
                               flat_number   TEXT,
                               role          TEXT,
                               batting_style TEXT,
                               bowling_style TEXT,
                               notes         TEXT,
                               status        TEXT    NOT NULL DEFAULT 'PENDING',     -- PENDING | AWAITING_APPROVAL | APPROVED | REJECTED
                               approval_id   INTEGER REFERENCES admin_approvals(id) ON DELETE SET NULL,
                               player_id     INTEGER REFERENCES players(id)         ON DELETE SET NULL,
                               created_at    TEXT    NOT NULL
);
CREATE INDEX idx_registrations_society    ON registrations(society_id);
CREATE INDEX idx_registrations_season     ON registrations(season_id);
CREATE INDEX idx_registrations_status     ON registrations(status);
CREATE INDEX idx_registrations_approval   ON registrations(approval_id);
CREATE INDEX idx_registrations_player     ON registrations(player_id);
CREATE INDEX idx_registrations_created_at ON registrations(created_at);

CREATE TABLE auction_lots (
                              id               INTEGER PRIMARY KEY AUTOINCREMENT,
                              society_id       INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                              season_id        INTEGER NOT NULL REFERENCES seasons(id)   ON DELETE CASCADE,
                              player_id        INTEGER NOT NULL REFERENCES players(id)   ON DELETE CASCADE,
                              base_price       REAL    NOT NULL DEFAULT 0,
                              current_bid      REAL    NOT NULL DEFAULT 0,
                              current_bid_team_id INTEGER REFERENCES teams(id) ON DELETE SET NULL,
                              sold_price       REAL,
                              sold_team_id     INTEGER REFERENCES teams(id) ON DELETE SET NULL,
                              status           TEXT    NOT NULL DEFAULT 'PENDING',  -- PENDING | LIVE | SOLD | UNSOLD
                              mode             TEXT    NOT NULL DEFAULT 'ONLINE',   -- ONLINE | OFFLINE
                              created_at       TEXT    NOT NULL
);
CREATE INDEX idx_lots_society ON auction_lots(society_id);
CREATE INDEX idx_lots_season  ON auction_lots(season_id);
CREATE INDEX idx_lots_player  ON auction_lots(player_id);
CREATE INDEX idx_lots_status  ON auction_lots(status);
CREATE INDEX idx_lots_bid_team  ON auction_lots(current_bid_team_id);
CREATE INDEX idx_lots_sold_team ON auction_lots(sold_team_id);

CREATE TABLE auction_bids (
                              id        INTEGER PRIMARY KEY AUTOINCREMENT,
                              lot_id    INTEGER NOT NULL REFERENCES auction_lots(id) ON DELETE CASCADE,
                              team_id   INTEGER NOT NULL REFERENCES teams(id)        ON DELETE CASCADE,
                              amount    REAL    NOT NULL,
                              bid_at    TEXT    NOT NULL
);
CREATE INDEX idx_bids_lot  ON auction_bids(lot_id);
CREATE INDEX idx_bids_team ON auction_bids(team_id);

CREATE TABLE matches (
                         id              INTEGER PRIMARY KEY AUTOINCREMENT,
                         society_id      INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                         season_id       INTEGER NOT NULL REFERENCES seasons(id)   ON DELETE CASCADE,
                         team_a_id       INTEGER NOT NULL REFERENCES teams(id)     ON DELETE CASCADE,
                         team_b_id       INTEGER NOT NULL REFERENCES teams(id)     ON DELETE CASCADE,
                         match_date      TEXT,
                         venue           TEXT,
                         stage           TEXT,
                         status          TEXT    NOT NULL DEFAULT 'SCHEDULED',
                         team_a_runs     INTEGER,
                         team_a_wickets  INTEGER,
                         team_a_overs    REAL,
                         team_b_runs     INTEGER,
                         team_b_wickets  INTEGER,
                         team_b_overs    REAL,
                         winner_team_id  INTEGER REFERENCES teams(id) ON DELETE SET NULL,
                         result_summary  TEXT,
                         created_at      TEXT    NOT NULL,
                         CHECK (team_a_id <> team_b_id)
);
CREATE INDEX idx_matches_society ON matches(society_id);
CREATE INDEX idx_matches_season  ON matches(season_id);
CREATE INDEX idx_matches_team_a  ON matches(team_a_id);
CREATE INDEX idx_matches_team_b  ON matches(team_b_id);
CREATE INDEX idx_matches_winner  ON matches(winner_team_id);
CREATE INDEX idx_matches_status  ON matches(status);
CREATE INDEX idx_matches_date    ON matches(match_date);

CREATE TABLE sponsors (
                          id          INTEGER PRIMARY KEY AUTOINCREMENT,
                          society_id  INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                          season_id   INTEGER REFERENCES seasons(id) ON DELETE SET NULL,
                          name        TEXT    NOT NULL,
                          tier        TEXT,
                          logo_url    TEXT,
                          website     TEXT,
                          amount      REAL    NOT NULL DEFAULT 0,
                          active      INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
                          created_at  TEXT    NOT NULL
);
CREATE INDEX idx_sponsors_society ON sponsors(society_id);
CREATE INDEX idx_sponsors_season  ON sponsors(season_id);

CREATE TABLE awards (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        society_id  INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                        season_id   INTEGER REFERENCES seasons(id) ON DELETE SET NULL,
                        player_id   INTEGER REFERENCES players(id) ON DELETE SET NULL,
                        team_id     INTEGER REFERENCES teams(id)   ON DELETE SET NULL,
                        title       TEXT    NOT NULL,
                        category    TEXT,
                        description TEXT,
                        image_url   TEXT,
                        created_at  TEXT    NOT NULL
);
CREATE INDEX idx_awards_society ON awards(society_id);
CREATE INDEX idx_awards_season  ON awards(season_id);
CREATE INDEX idx_awards_player  ON awards(player_id);
CREATE INDEX idx_awards_team    ON awards(team_id);

CREATE TABLE gallery_images (
                                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                                society_id  INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                                season_id   INTEGER REFERENCES seasons(id) ON DELETE SET NULL,
                                title       TEXT,
                                image_url   TEXT    NOT NULL,
                                caption     TEXT,
                                created_at  TEXT    NOT NULL
);
CREATE INDEX idx_gallery_society ON gallery_images(society_id);
CREATE INDEX idx_gallery_season  ON gallery_images(season_id);

-- ---------------------------------------------------------------------
-- Vendors & bills
-- ---------------------------------------------------------------------
CREATE TABLE vendor_contacts (
                                 id              INTEGER PRIMARY KEY AUTOINCREMENT,
                                 society_id      INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                                 name            TEXT    NOT NULL,
                                 category        TEXT    NOT NULL
                                     CHECK (category IN ('VENUE','TSHIRT_PRINTING','LIGHTING','DECORATION',
                                                         'EVENT_ORGANISER','FOOD_BEVERAGE','ELECTRICIAN',
                                                         'MEDICAL_STAFF','UMPIRE','TROPHIES','GIFTS',
                                                         'CAMERAMAN','OTHER')),
                                 contact_person  TEXT,
                                 phone           TEXT,
                                 whatsapp_number TEXT,
                                 email           TEXT,
                                 address         TEXT,
                                 city            TEXT,
                                 website         TEXT,
                                 rating          INTEGER CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
                                 notes           TEXT,
                                 active          INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
                                 created_at      TEXT    NOT NULL
);
CREATE INDEX idx_vendors_society    ON vendor_contacts(society_id);
CREATE INDEX idx_vendors_category   ON vendor_contacts(category);
CREATE INDEX idx_vendors_created_at ON vendor_contacts(created_at);

CREATE TABLE vendor_bills (
                              id             INTEGER PRIMARY KEY AUTOINCREMENT,
                              society_id     INTEGER NOT NULL REFERENCES societies(id)       ON DELETE CASCADE,
                              vendor_id      INTEGER NOT NULL REFERENCES vendor_contacts(id) ON DELETE CASCADE,
                              season_id      INTEGER REFERENCES seasons(id) ON DELETE SET NULL,
                              title          TEXT    NOT NULL,
                              amount         REAL    NOT NULL DEFAULT 0,
                              bill_date      TEXT,
                              paid_date      TEXT,
                              status         TEXT    NOT NULL DEFAULT 'PENDING'
                                  CHECK (status IN ('PENDING','PAID','DISPUTED')),
                              description    TEXT,
                              attachment_url TEXT,
                              approved_by    INTEGER REFERENCES users(id) ON DELETE SET NULL,
                              created_at     TEXT    NOT NULL
);
CREATE INDEX idx_bills_society     ON vendor_bills(society_id);
CREATE INDEX idx_bills_vendor      ON vendor_bills(vendor_id);
CREATE INDEX idx_bills_season      ON vendor_bills(season_id);
CREATE INDEX idx_bills_approved_by ON vendor_bills(approved_by);
CREATE INDEX idx_bills_status      ON vendor_bills(status);
CREATE INDEX idx_bills_created_at  ON vendor_bills(created_at);

-- ---------------------------------------------------------------------
-- Subscription & billing
-- ---------------------------------------------------------------------
CREATE TABLE subscriptions (
                               id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                               society_id         INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                               plan               TEXT    NOT NULL
                                   CHECK (plan IN ('FREE_TRIAL','BASIC','STANDARD','PREMIUM')),
                               status             TEXT    NOT NULL
                                   CHECK (status IN ('TRIAL','ACTIVE','PAST_DUE','CANCELLED')),
                               started_at         TEXT    NOT NULL,
                               expires_at         TEXT,
                               monthly_cost       REAL    NOT NULL DEFAULT 0,
                               currency           TEXT    NOT NULL DEFAULT 'INR',
                               auto_renew         INTEGER NOT NULL DEFAULT 1 CHECK (auto_renew IN (0,1)),
                               stripe_customer_id TEXT,
                               last_payment_at    TEXT,
                               next_billing_at    TEXT,
                               past_due_since     TEXT,        -- drives the 30-day read-only cutoff
                               created_at         TEXT    NOT NULL
);
CREATE INDEX idx_subscriptions_society ON subscriptions(society_id);
CREATE INDEX idx_subscriptions_status  ON subscriptions(status);
CREATE INDEX idx_subscriptions_created ON subscriptions(created_at);

CREATE TABLE invoices (
                          id              INTEGER PRIMARY KEY AUTOINCREMENT,
                          society_id      INTEGER NOT NULL REFERENCES societies(id)     ON DELETE CASCADE,
                          subscription_id INTEGER NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
                          invoice_number  TEXT    NOT NULL,
                          amount          REAL    NOT NULL,
                          currency        TEXT    NOT NULL DEFAULT 'INR',
                          status          TEXT    NOT NULL DEFAULT 'ISSUED',
                          issued_at       TEXT    NOT NULL,
                          paid_at         TEXT
);
CREATE UNIQUE INDEX uq_invoices_number ON invoices(invoice_number);
CREATE INDEX idx_invoices_society      ON invoices(society_id);
CREATE INDEX idx_invoices_subscription ON invoices(subscription_id);

-- ---------------------------------------------------------------------
-- Platform content & sharing
-- ---------------------------------------------------------------------
CREATE TABLE platform_events (
                                 id            INTEGER PRIMARY KEY AUTOINCREMENT,
                                 title         TEXT    NOT NULL,
                                 description   TEXT,
                                 event_date    TEXT,
                                 scope         TEXT    NOT NULL DEFAULT 'PLATFORM' CHECK (scope IN ('PLATFORM','SOCIETY')),
                                 society_id    INTEGER REFERENCES societies(id) ON DELETE CASCADE,
                                 type          TEXT    NOT NULL DEFAULT 'OTHER'
                                     CHECK (type IN ('MATCH','AUCTION','TOURNAMENT','REGISTRATION_OPEN','AWARDS','OTHER')),
                                 thumbnail_url TEXT,
                                 external_link TEXT,
                                 published     INTEGER NOT NULL DEFAULT 0 CHECK (published IN (0,1)),
                                 created_at    TEXT    NOT NULL
);
CREATE INDEX idx_events_society    ON platform_events(society_id);
CREATE INDEX idx_events_date       ON platform_events(event_date);
CREATE INDEX idx_events_created_at ON platform_events(created_at);

CREATE TABLE social_shares (
                               id          INTEGER PRIMARY KEY AUTOINCREMENT,
                               token       TEXT    NOT NULL,                      -- public /share/{token} landing page
                               user_id     INTEGER NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
                               player_id   INTEGER REFERENCES players(id) ON DELETE SET NULL,
                               team_id     INTEGER REFERENCES teams(id)   ON DELETE SET NULL,
                               match_id    INTEGER REFERENCES matches(id) ON DELETE SET NULL,
                               award_id    INTEGER REFERENCES awards(id)  ON DELETE SET NULL,
                               platform    TEXT    NOT NULL
                                   CHECK (platform IN ('INSTAGRAM','FACEBOOK','WHATSAPP','TWITTER_X','LINKEDIN','COPY_LINK')),
                               caption     TEXT,
                               image_url   TEXT,
                               share_url   TEXT,
                               created_at  TEXT    NOT NULL
);
CREATE UNIQUE INDEX uq_shares_token ON social_shares(token);
CREATE INDEX idx_shares_user   ON social_shares(user_id);
CREATE INDEX idx_shares_player ON social_shares(player_id);
CREATE INDEX idx_shares_team   ON social_shares(team_id);
CREATE INDEX idx_shares_match  ON social_shares(match_id);
CREATE INDEX idx_shares_award  ON social_shares(award_id);
CREATE INDEX idx_shares_created_at ON social_shares(created_at);

-- ---------------------------------------------------------------------
-- Import / backup
-- ---------------------------------------------------------------------
CREATE TABLE upload_batches (
                                id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                                society_id         INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                                uploaded_by        INTEGER NOT NULL REFERENCES users(id)     ON DELETE CASCADE,
                                original_file_name TEXT    NOT NULL,
                                file_size_bytes    INTEGER NOT NULL,
                                mime_type          TEXT,
                                status             TEXT    NOT NULL DEFAULT 'UPLOADED'
                                    CHECK (status IN ('UPLOADED','SEGREGATING','READY_FOR_REVIEW','APPROVED',
                                                      'PARTIALLY_APPROVED','REJECTED','FAILED')),
                                segregation_report TEXT,
                                review_file_url    TEXT,
                                failed_file_url    TEXT,
                                approval_id        INTEGER REFERENCES admin_approvals(id) ON DELETE SET NULL,
                                review_deadline    TEXT,
                                approved_at        TEXT,
                                approved_by        INTEGER REFERENCES users(id) ON DELETE SET NULL,
                                error_message      TEXT,
                                created_at         TEXT    NOT NULL
);
CREATE INDEX idx_batches_society     ON upload_batches(society_id);
CREATE INDEX idx_batches_uploaded_by ON upload_batches(uploaded_by);
CREATE INDEX idx_batches_approved_by ON upload_batches(approved_by);
CREATE INDEX idx_batches_approval    ON upload_batches(approval_id);
CREATE INDEX idx_batches_status      ON upload_batches(status);
CREATE INDEX idx_batches_created_at  ON upload_batches(created_at);

CREATE TABLE backup_records (
                                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                                society_id      INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                                requested_by    INTEGER REFERENCES users(id) ON DELETE SET NULL,
                                file_name       TEXT    NOT NULL,
                                file_size_bytes INTEGER,
                                encrypted       INTEGER NOT NULL DEFAULT 1 CHECK (encrypted IN (0,1)),
                                algorithm       TEXT,
                                sha256          TEXT,
                                status          TEXT    NOT NULL DEFAULT 'GENERATING'
                                    CHECK (status IN ('GENERATING','READY','DOWNLOADED','FAILED')),
                                created_at      TEXT    NOT NULL,
                                expires_at      TEXT
);
CREATE INDEX idx_backups_society      ON backup_records(society_id);
CREATE INDEX idx_backups_requested_by ON backup_records(requested_by);
CREATE INDEX idx_backups_status       ON backup_records(status);
CREATE INDEX idx_backups_created_at   ON backup_records(created_at);
CREATE INDEX idx_backups_expires_at   ON backup_records(expires_at);

-- ---------------------------------------------------------------------
-- Cross-society tournaments (scaffold; UI feature-flagged off)
-- ---------------------------------------------------------------------
CREATE TABLE cross_society_tournaments (
                                           id              INTEGER PRIMARY KEY AUTOINCREMENT,
                                           name            TEXT    NOT NULL,
                                           host_society_id INTEGER NOT NULL REFERENCES societies(id) ON DELETE CASCADE,
                                           start_date      TEXT,
                                           end_date        TEXT,
                                           status          TEXT    NOT NULL DEFAULT 'PROPOSED'
                                               CHECK (status IN ('PROPOSED','APPROVING','APPROVED','IN_PROGRESS','COMPLETED','CANCELLED')),
                                           description     TEXT,
                                           created_at      TEXT    NOT NULL
);
CREATE INDEX idx_tournaments_host   ON cross_society_tournaments(host_society_id);
CREATE INDEX idx_tournaments_status ON cross_society_tournaments(status);

CREATE TABLE tournament_societies (
                                      tournament_id INTEGER NOT NULL REFERENCES cross_society_tournaments(id) ON DELETE CASCADE,
                                      society_id    INTEGER NOT NULL REFERENCES societies(id)                 ON DELETE CASCADE,
                                      PRIMARY KEY (tournament_id, society_id)
);
CREATE INDEX idx_tournament_societies_society ON tournament_societies(society_id);

-- ---------------------------------------------------------------------
-- Notifications & audit
-- ---------------------------------------------------------------------
CREATE TABLE notifications (
                               id          INTEGER PRIMARY KEY AUTOINCREMENT,
                               user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                               society_id  INTEGER REFERENCES societies(id) ON DELETE CASCADE,
                               title       TEXT    NOT NULL,
                               message     TEXT,
                               link        TEXT,
                               is_read     INTEGER NOT NULL DEFAULT 0 CHECK (is_read IN (0,1)),
                               created_at  TEXT    NOT NULL
);
CREATE INDEX idx_notifications_user    ON notifications(user_id);
CREATE INDEX idx_notifications_society ON notifications(society_id);
CREATE INDEX idx_notifications_unread  ON notifications(user_id, created_at) WHERE is_read = 0;

-- Deliberately no FK on society_id/user_id cascade: audit rows must outlive the
-- entities they describe, so the user link is SET NULL and society_id is a plain column.
CREATE TABLE audit_logs (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            user_id     INTEGER REFERENCES users(id) ON DELETE SET NULL,
                            society_id  INTEGER,
                            action      TEXT    NOT NULL,
                            entity_type TEXT,
                            entity_id   INTEGER,
                            old_value   TEXT,
                            new_value   TEXT,
                            ip_address  TEXT,
                            user_agent  TEXT,
                            timestamp   TEXT    NOT NULL
);
CREATE INDEX idx_audit_user      ON audit_logs(user_id);
CREATE INDEX idx_audit_society   ON audit_logs(society_id);
CREATE INDEX idx_audit_action    ON audit_logs(action);
CREATE INDEX idx_audit_timestamp ON audit_logs(timestamp);