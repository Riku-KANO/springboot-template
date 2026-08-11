-- 外部決済と注文更新を1つのDBトランザクションに見せかけないための永続的な処理状態。
-- idempotency_key は決済プロバイダにも同じ値を渡し、通信切断やプロセス停止後の再試行を
-- 同一の請求へ収束させる。
CREATE TABLE payment_attempts (
    idempotency_key         VARCHAR(128) NOT NULL PRIMARY KEY,
    order_id                VARCHAR(64) NOT NULL UNIQUE REFERENCES orders(id),
    amount_minor            BIGINT NOT NULL CHECK (amount_minor >= 0),
    currency                VARCHAR(3) NOT NULL,
    status                  VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED')),
    paid_at                 TIMESTAMPTZ,
    provider_transaction_id VARCHAR(255) UNIQUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT payment_attempt_result_consistency CHECK (
        (status = 'PENDING' AND paid_at IS NULL AND provider_transaction_id IS NULL)
        OR
        (status = 'SUCCEEDED' AND paid_at IS NOT NULL AND provider_transaction_id IS NOT NULL)
    )
);
