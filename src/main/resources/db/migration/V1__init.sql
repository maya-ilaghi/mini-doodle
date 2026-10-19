-- Extensions ----------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pgcrypto;    -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS btree_gist;  -- allows '=' on scalar cols inside a GiST EXCLUDE constraint

-- Users ---------------------------------------------------------------------
CREATE TABLE app_user (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(320) NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Calendar (internal domain concept: exactly one per user) -------------------
CREATE TABLE calendar (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE CASCADE,
    time_zone  VARCHAR(64) NOT NULL DEFAULT 'UTC',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Time slots ----------------------------------------------------------------
CREATE TABLE time_slot (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    calendar_id UUID NOT NULL REFERENCES calendar(id) ON DELETE CASCADE,
    start_time  TIMESTAMPTZ NOT NULL,
    end_time    TIMESTAMPTZ NOT NULL,
    during      TSTZRANGE GENERATED ALWAYS AS (tstzrange(start_time, end_time, '[)')) STORED,
    status      VARCHAR(16) NOT NULL DEFAULT 'FREE',
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT time_slot_time_valid   CHECK (end_time > start_time),
    CONSTRAINT time_slot_status_valid CHECK (status IN ('FREE','BUSY')),
    -- No two slots in the same calendar may overlap (enforced atomically).
    CONSTRAINT time_slot_no_overlap
        EXCLUDE USING gist (calendar_id WITH =, during WITH &&)
);

CREATE INDEX idx_time_slot_calendar_start ON time_slot (calendar_id, start_time);
CREATE INDEX idx_time_slot_status         ON time_slot (calendar_id, status);

-- Meetings (a booked slot, 1:1) ---------------------------------------------
CREATE TABLE meeting (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slot_id     UUID NOT NULL UNIQUE REFERENCES time_slot(id) ON DELETE CASCADE,
    title       VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Participants (by email; optional link to a registered user) ---------------
CREATE TABLE meeting_participant (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meeting_id      UUID NOT NULL REFERENCES meeting(id) ON DELETE CASCADE,
    email           VARCHAR(320) NOT NULL,
    user_id         UUID REFERENCES app_user(id) ON DELETE SET NULL,
    response_status VARCHAR(16)  NOT NULL DEFAULT 'INVITED',
    CONSTRAINT meeting_participant_status_valid
        CHECK (response_status IN ('INVITED','ACCEPTED','DECLINED')),
    CONSTRAINT meeting_participant_unique UNIQUE (meeting_id, email)
);

CREATE INDEX idx_participant_meeting ON meeting_participant (meeting_id);