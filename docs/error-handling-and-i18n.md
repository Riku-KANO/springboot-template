# エラーハンドリングと多言語対応

このテンプレートでは、エラーを次の3つに分けて扱う。

1. **ドメイン上の失敗**: `DomainError` の sealed hierarchy。業務判断と診断情報を表す。
2. **機械可読なAPI契約**: `ORDER_NOT_FOUND` のような安定した `code`。
3. **人間向け表示文言**: `Accept-Language` に応じてWeb境界で生成するローカライズ済みメッセージ。

ドメイン層は `MessageSource`、`Locale`、HTTPを知らない。翻訳は
`adapter-web/problem/DomainErrorProblemMapper.kt`だけで行い、文言は
`adapter-web/src/main/resources/messages*.properties`に置く。

## レスポンス形式

英語は既定値。日本語は `Accept-Language: ja` または `ja-JP` で要求する。

```http
GET /orders/missing-order
Accept-Language: ja-JP, en;q=0.8
```

```json
{
  "type": "https://errors.example.com/problems/order-not-found",
  "title": "見つかりません",
  "status": 404,
  "detail": "注文「missing-order」が見つかりません。",
  "requestId": "...",
  "code": "ORDER_NOT_FOUND",
  "errors": [
    {
      "code": "ORDER_NOT_FOUND",
      "message": "注文「missing-order」が見つかりません。"
    }
  ]
}
```

クライアントは `detail` や `message` を条件分岐に使わず、必ず `code` を使う。表示文言は翻訳改善に
よって変更される可能性があるが、`code` はAPI互換性の一部として維持する。

累積バリデーションでは、代表エラーをトップレベルの `code` / `detail` に置き、全件を `errors` 配列で返す。

## 安全な公開メッセージ

`RepositoryUnavailable.cause` などの診断情報は、ログや運用調査には必要だが公開APIへ出してはいけない。
SQL、ホスト名、制約名、外部サービスのレスポンスが漏れる可能性があるためである。

`DomainErrorProblemMapper`はインフラエラーに固定の安全な公開文言を割り当てる。予期しない例外は
`GlobalExceptionHandler`がrequestId付きでログへ記録し、レスポンスには`UNEXPECTED_ERROR`だけを返す。

## エラーを追加する手順

1. 適切なsealed hierarchyに新しいエラーvariantを追加する。
2. `DomainErrorProblemMapper.descriptor`のexhaustiveな`when`へ安定コード、message key、引数を追加する。
3. `messages.properties`と`messages_ja.properties`へ同じkeyを追加する。
4. HTTPステータス、コード、日英文言、内部原因の非公開をテストする。

`when`に`else`を書かないため、ADTのvariant追加時にWeb表現の定義漏れはコンパイルエラーになる。

## 対応言語を増やす

例えばフランス語を追加する場合は、`messages_fr.properties`を追加し、`ServerWebExchange.apiLocale`の
対応言語へ`fr`を加える。未対応言語と`Accept-Language`省略時は英語へフォールバックする。
