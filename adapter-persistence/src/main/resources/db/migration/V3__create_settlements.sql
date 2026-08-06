-- ============================================================================
-- settlements: SettlementRepository.recordOutcome が書き込む消込結果のうち、
--              「問題なく処理できた」もの (ReconciliationOutcome.Matched /
--              AlreadySettled) だけを溜める正常系の台帳。
--
-- ReconciliationOutcome は4バリアントの sealed interface (Matched / AmountMismatch /
-- OrderNotSettleable / AlreadySettled) だが、あえて2テーブルに分けて保存する:
--   - このテーブル (settlements)      : Matched / AlreadySettled (要対応なし)
--   - V4 (settlement_errors)          : AmountMismatch / OrderNotSettleable (要確認)
--
-- 1テーブルにまとめて outcome_type 列で判別する案もあったが、AmountMismatch /
-- OrderNotSettleable にだけ必要なペイロード列 (期待金額・実際の金額・注文の状態) を
-- 正常系の行にまで nullable 列として持たせることになり、「オペレーターが毎日見るべき
-- 例外キューはどれか」が一目で分からなくなる。2テーブルに分けることで、
-- V4 だけを見れば「今日確認が必要な消込」が過不足なく取得できる。
--
-- outcome_type を CHECK 制約で MATCHED / ALREADY_SETTLED の2値に絞っているのは、
-- アプリ側の書き込みロジック (SettlementRepositoryAdapter) が `when (outcome) { ... }`
-- を `else` 無しで網羅しており、AmountMismatch / OrderNotSettleable がここに来ることは
-- 構造的に無い、という前提を DB 側でも二重に保証するため。
-- ============================================================================
CREATE TABLE settlements (
    id                       BIGSERIAL     NOT NULL PRIMARY KEY,
    order_id                 VARCHAR(64)   NOT NULL REFERENCES orders (id),
    settled_amount_minor     BIGINT        NOT NULL,
    settled_amount_currency  VARCHAR(3)    NOT NULL,
    settled_at               TIMESTAMPTZ   NOT NULL,
    provider_transaction_id  VARCHAR(255)  NOT NULL,
    outcome_type             VARCHAR(32)   NOT NULL CHECK (outcome_type IN ('MATCHED', 'ALREADY_SETTLED')),
    recorded_at              TIMESTAMPTZ   NOT NULL
);
