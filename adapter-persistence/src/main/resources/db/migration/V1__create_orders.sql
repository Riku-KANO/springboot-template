-- ============================================================================
-- orders: Order 集約のルート1行分。
--
-- OrderStatus (8 バリアントの sealed interface) の永続化方式について:
--
--   discriminator 列 (status_type) + バリアントごとのペイロードを保持する nullable 列、
--   という表現を選んだ (JSONB 1カラムにまとめる案は採らなかった)。理由:
--
--   1. このテンプレートは「ADT の網羅性チェックをコンパイラに強制させる」ことを
--      一貫したテーマにしている (domain/order/OrderTransitions.kt 等参照)。
--      status を素の JSONB にすると、DDL からはどのバリアントが存在しうるかが
--      一切読み取れなくなり、「新しい状態を追加したら DDL 側も直す」意識が働かない。
--      列を明示すれば、テーブル定義自体が「今どんな状態がありうるか」のドキュメントになる。
--   2. Paid.paidAt や Refunded.amount のように、状態ごとのペイロードに対して
--      将来 SQL 側で範囲検索や集計をしたくなる場面がありうる。JSONB 内部のフィールドに
--      対して同じことをするのは相対的に面倒 (式インデックス等が必要になる)。
--
--   代償は「バリアントの数だけ nullable 列が増える」ことだが、Order の状態バリアントは
--   今後も大きく増減しない想定であり、可読性・検索性を優先した。
--   アプリ側 (adapter/persistence/order/OrderStatusMapping.kt) の
--   OrderStatus -> 列 の変換は `when` に `else` を書かず全バリアントを網羅しており、
--   9番目の状態が増えた瞬間にコンパイルエラーとして検出できるようにしてある。
-- ============================================================================
CREATE TABLE orders (
    id                               VARCHAR(64)   NOT NULL PRIMARY KEY,
    customer_id                      VARCHAR(64)   NOT NULL,

    -- ShippingAddress (配送先住所)。ドメイン側に住所専用のスマートコンストラクタは無く
    -- (ShippingAddress はバリデーションを持たない素の data class)、列もそのまま素直に対応させる。
    recipient_name                   VARCHAR(255)  NOT NULL,
    postal_code                      VARCHAR(32)   NOT NULL,
    prefecture                       VARCHAR(255)  NOT NULL,
    city                             VARCHAR(255)  NOT NULL,
    address_line1                    VARCHAR(255)  NOT NULL,
    address_line2                    VARCHAR(255),

    -- OrderStatus の discriminator。値は DRAFT / PENDING_PAYMENT / PAID / FULFILLING /
    -- SHIPPED / DELIVERED / CANCELLED / REFUNDED の8種 (OrderStatusMapping.kt 参照)。
    status_type                      VARCHAR(32)   NOT NULL,

    -- 以下、バリアントごとのペイロード。同時に非 NULL になるのは高々1グループだけ
    -- (アプリ側の変換ロジックが保証する。DB 側では CHECK 制約までは設けていない)。
    status_paid_at                   TIMESTAMPTZ,
    status_fulfilling_started_at     TIMESTAMPTZ,
    status_tracking_number           VARCHAR(255),
    status_delivered_at              TIMESTAMPTZ,
    status_cancelled_reason          VARCHAR(1000),
    status_refunded_at               TIMESTAMPTZ,
    status_refunded_amount_minor     BIGINT,
    status_refunded_amount_currency  VARCHAR(3)
);
