-- プロバイダ取引IDを全消込結果テーブル横断で一度だけ処理するための受領台帳。
CREATE TABLE settlement_record_claims (
    provider_transaction_id VARCHAR(255) NOT NULL PRIMARY KEY,
    order_id                 VARCHAR(64) NOT NULL,
    claimed_at               TIMESTAMPTZ NOT NULL
);

-- バッチ監査ログをJVM内の共有リストではなくJobInstance単位で再集計できるようにする。
ALTER TABLE batch_settlement_results ADD COLUMN job_instance_id BIGINT;
UPDATE batch_settlement_results SET job_instance_id = -id WHERE job_instance_id IS NULL;
ALTER TABLE batch_settlement_results ALTER COLUMN job_instance_id SET NOT NULL;
CREATE UNIQUE INDEX uq_batch_settlement_result_job_transaction
    ON batch_settlement_results (job_instance_id, provider_transaction_id);

ALTER TABLE batch_settlement_errors ADD COLUMN job_instance_id BIGINT;
UPDATE batch_settlement_errors SET job_instance_id = -id WHERE job_instance_id IS NULL;
ALTER TABLE batch_settlement_errors ALTER COLUMN job_instance_id SET NOT NULL;
