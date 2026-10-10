ALTER TABLE agent_conversation
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_agent_conversation_version_non_negative
        CHECK (version >= 0);