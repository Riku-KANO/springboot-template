-- ============================================================================
-- order_lines: Order.lines (NonEmptyList<OrderLine>) の各要素。
--
-- line_no で NonEmptyList の順序 (head が先頭) を保持する。NonEmptyList 自体には
-- 「明細のインデックス」という概念は無いが、保存/復元のたびに順序が入れ替わっては
-- ラウンドトリップの等価性 (save -> findById で同じ Order に戻ること) が壊れるため、
-- 保存時に 0 始まりの連番を付与し、復元時は line_no 昇順に並べて NonEmptyList を組み立てる
-- (adapter/persistence/order/OrderMapper.kt 参照)。
--
-- 金額は MoneyMinor (最小通貨単位の Long) + 通貨コードの組で持つ。ドメイン側の
-- Money と同じ形をそのまま列に落としているだけで、BigDecimal 変換は一切ここに持ち込まない
-- (丸め誤差の混入経路を増やさないため。詳細は domain/shared/MoneyMinor.kt のコメント参照)。
--
-- Order 集約は「明細を1件も持たない注文が存在しない」ことを NonEmptyList という型で
-- 保証しているが、DB レベルでそれを強制する CHECK 制約までは設けていない
-- (集約全体の削除・置換は常にアプリ層のトランザクション内で行われる前提のため)。
-- 代わりに、復元時に order_lines が0件だった場合はアプリ側 (OrderMapper.kt) が
-- OrderError.RepositoryUnavailable を返し、壊れた集約をそのまま Order にはしない。
-- ============================================================================
CREATE TABLE order_lines (
    order_id             VARCHAR(64)  NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    line_no              INT          NOT NULL,
    sku                  VARCHAR(32)  NOT NULL,
    quantity             INT          NOT NULL,
    unit_price_minor     BIGINT       NOT NULL,
    unit_price_currency  VARCHAR(3)   NOT NULL,
    PRIMARY KEY (order_id, line_no)
);
