-- Apply once to the dedicated myct database, as its owner, with ON_ERROR_STOP.
BEGIN;
CREATE TABLE public.execution_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_type text NOT NULL CHECK (
        length(event_type) BETWEEN 1 AND 64
        AND event_type COLLATE "C" !~ '[^a-zA-Z0-9_.-]'
    )
);
COMMIT;
