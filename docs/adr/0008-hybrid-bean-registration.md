# ADR 0008: Bean 定義は関数型 DSL と `@Configuration` のハイブリッドにする

## ステータス

Accepted

## コンテキスト

Spring には大きく2つの Bean 登録スタイルがある。

- アノテーションベース (`@Component`/`@Configuration` + `@Bean`)
- 関数型 (`BeanRegistrarDsl` を `@Import` する)

「関数型プログラミングのテンプレート」を謳う以上、全てを関数型 DSL に統一したくなるが、
実際に手を動かすと Spring Security の `ServerHttpSecurity` DSL、条件付き自動構成
(`@ConditionalOnMissingBean` 等) と噛み合わせる必要のある設定は、関数型 DSL で無理に書こうとすると
かえって複雑になることが分かった。

## 決定

**どちらか一方の流儀に統一することにこだわらない。** 使い分けの基準は以下の通り。

- **`BeanRegistrarDsl` を使う場所**: `:application` のユースケース実装や `:adapter-persistence`
  のポート実装のように、意図的に Spring フリー・非アノテーションで書かれたプレーンな Kotlin
  クラスをコンストラクタ注入するだけの箇所 (`bootstrap/.../config/UseCaseBeans.kt`,
  `PersistenceBeans.kt`, `PaymentGatewayBeans.kt`)。「ただ依存を解決してインスタンス化するだけ」
  なので、`@Configuration` + `@Bean` メソッドを並べるより登録DSLの方が単純に薄く済む。
- **`@Configuration` + `@Bean` を使う場所**: `SecurityConfig`/`LocalSecurityConfig`
  (`ServerHttpSecurity` DSL 前提)、`JdbcDataSourceConfig`/`R2dbcTransactionConfig`
  (Boot の条件付き自動構成と噛み合わせる必要がある)、`AwsClientsConfig` (`AwsClientBuilderConfigurer`
  という Spring Cloud AWS 自身のヘルパーを再利用する) のように、フレームワークから渡される
  ビルダーオブジェクトを段階的に組み立てる形が前提の箇所、または自動構成の `@ConditionalOnMissingBean`
  判定に「ユーザー定義の Bean として認識される」必要がある箇所。

Spring Framework 7で非推奨になった `org.springframework.context.support.beans { }` から、
後継の `BeanRegistrarDsl` へ移行済み。3つのRegistrarは `TemplateApplication` が明示的に
`@Import` するため、起動方法やテストごとのInitializer登録に依存しない。

## 帰結

**良い点**

- 「フレームワークと戦うコストがメリットを上回る場所では、素直にフレームワークの流儀に従う」
  という現実的な態度を、コード自体で示せる。関数型に教条的なテンプレートは、実務でフレームワークの
  自動構成と衝突した瞬間に「破られる原則」になりがちだが、このテンプレートは最初からその
  境界線を明示している。
- 新しくユースケースやポート実装を追加する際の判断基準 (「Spring アノテーション無しで書けるか」)
  がそのまま Bean 登録方法の選択基準にもなるため、追加のドキュメントが無くても迷いにくい。

**トレードオフ・注意点**

- Registrarは `@Import` されたcomposition rootの一部なので、単独のslice testでは必要なRegistrarを
  明示的にimportする必要がある。
- 「このクラスはどちらのスタイルで登録すべきか」の判断基準がコードのコメントに散らばっており、
  一箇所にまとまった判断フローチャートのようなものは無い。新しいメンバーが最初に迷う点は
  引き続きこの ADR と各設定クラスの KDoc を読んでもらうことになる。
