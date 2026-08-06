-- ============================================================================
-- settlement_errors: ReconciliationOutcome のうち「要確認」の2バリアント
--                     (AmountMismatch / OrderNotSettleable) の専用テーブル。
--                     V3 (settlements) のコメントに設計意図を書いた通り、
--                     オペレーターが確認すべき消込だけをここに集約する。
--
-- expected_/actual_amount_* は AmountMismatch のペイロード、order_status_* は
-- OrderNotSettleable のペイロード (突き合わせ時点での注文の状態) であり、
-- どちらか一方のグループだけが非 NULL になる (outcome_type で判別する)。
--
-- order_status_* の列構成は V1 (orders テーブル) の status_* 列と全く同じ形にしてある。
-- これは偶然の一致ではなく、OrderStatus -> 列 の変換関数
-- (adapter/persistence/order/OrderStatusMapping.kt の OrderStatus.toColumns()) を
-- orders テーブルへの保存とこのテーブルへの保存の両方で「同一の1関数」として
-- 再利用するための設計。OrderNotSettleable.status は理屈上 OrderStatus の
-- どのバリアントも取りうる (現状の domain/settlement/Reconciliation.kt の実装では
-- Draft / Cancelled しか実際には現れないが、型としては8バリアント全体を受け取る)、
-- という将来の拡張にも同じ変換関数だけで追従できる。
--
-- なお、このテーブルは Chunk 3 (:adapter-persistence) の時点では「書き込みポート」を
-- 持たない (:application の SettlementRepository は recordOutcome 経由でしか書き込まず、
-- ReconcileSettlementService は AmountMismatch / OrderNotSettleable も
-- recordOutcome 経由でここへ書く想定)。読み出し用のユースケース・ポートは
-- まだ :application 側に無いため、集計・一覧参照は今のところ SQL を直接叩く運用を想定する。
-- ============================================================================
CREATE TABLE settlement_errors (
    id                                       BIGSERIAL     NOT NULL PRIMARY KEY,
    order_id                                 VARCHAR(64)   NOT NULL REFERENCES orders (id),
    settled_amount_minor                     BIGINT        NOT NULL,
    settled_amount_currency                  VARCHAR(3)    NOT NULL,
    settled_at                               TIMESTAMPTZ   NOT NULL,
    provider_transaction_id                  VARCHAR(255)  NOT NULL,
    outcome_type                             VARCHAR(32)   NOT NULL
        CHECK (outcome_type IN ('AMOUNT_MISMATCH', 'ORDER_NOT_SETTLEABLE')),

    -- AmountMismatch 用ペイロード
    expected_amount_minor                    BIGINT,
    expected_amount_currency                 VARCHAR(3),
    actual_amount_minor                      BIGINT,
    actual_amount_currency                   VARCHAR(3),

    -- OrderNotSettleable 用ペイロード (V1 orders の status_* 列と同一構成。理由は上記コメント参照)
    order_status_type                        VARCHAR(32),
    order_status_paid_at                     TIMESTAMPTZ,
    order_status_fulfilling_started_at       TIMESTAMPTZ,
    order_status_tracking_number             VARCHAR(255),
    order_status_delivered_at                TIMESTAMPTZ,
    order_status_cancelled_reason            VARCHAR(1000),
    order_status_refunded_at                 TIMESTAMPTZ,
    order_status_refunded_amount_minor       BIGINT,
    order_status_refunded_amount_currency    VARCHAR(3),

    recorded_at                              TIMESTAMPTZ   NOT NULL
);
