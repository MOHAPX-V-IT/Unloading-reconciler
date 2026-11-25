CREATE TABLE IF NOT EXISTS reconciliation_runs (
    id VARCHAR(36) PRIMARY KEY,
    owner VARCHAR(254) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    left_name VARCHAR(180) NOT NULL,
    right_name VARCHAR(180) NOT NULL,
    differences INTEGER NOT NULL,
    report_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS runs_owner ON reconciliation_runs(owner, created_at);
CREATE TABLE IF NOT EXISTS demo_imports (
    name VARCHAR(64) PRIMARY KEY
);
