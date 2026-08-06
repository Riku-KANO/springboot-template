# ADR 0006: 消込バッチの実行基盤に AWS Lambda ではなく ECS (Fargate) を選ぶ

## ステータス

Accepted

## コンテキスト

`settlementReconciliationJob` (Spring Batch) の実行環境として、サーバーレスの AWS Lambda と、
コンテナベースの ECS (Fargate) の両方が候補になる。

## 決定

ECS (Fargate) 上のワンショットタスクとして実行する (`infra/terraform/modules/ecs-task`、
Step Functions の `ecs:runTask.waitForTaskToken`)。Lambda は採用しない。理由は以下の通り。

1. **実行時間の制約が無い。** Lambda は最大15分のハード制限がある。消込対象の注文件数が
   増えた場合や、決済プロバイダ側の応答が遅い場合に、この制限に将来抵触するリスクを
   最初から負いたくない。ECS タスクには時間制限が無い (Step Functions 側の `TimeoutSeconds`
   だけが上限になり、これは運用側で自由に調整できる)。
2. **同じ Docker イメージ・同じ Spring コンテキスト構成をそのまま使い回せる。** :bootstrap の
   bootJar は `--spring.profiles.active=<env>,batch` を渡すだけで Web サーバーを起動する
   通常モードとバッチのワンショットモードを切り替えられる (`application-batch.yml` の
   `spring.main.web-application-type: none` 参照)。Lambda を使うなら、Spring Boot アプリケーション
   全体をカスタムランタイムまたは専用の Lambda 対応フレームワーク (Spring Cloud Function 等) に
   移植する追加コストが発生し、「API とバッチで実装を分岐させない」という設計の一貫性が崩れる。
3. **JDBC + R2DBC のデュアル接続、Spring Batch の `JobRepository` を含む「フルの Spring
   コンテキスト起動」が前提になっている。** Lambda のコールドスタートはこの規模の Spring
   コンテキスト起動と相性が悪く (数秒単位の起動遅延が呼び出しのたびに乗る)、ECS の
   Fargate タスクなら「1回の起動で1回のジョブを最後まで実行し終了する」という設計と自然に噛み合う。
4. **`arrow-resilience` の `CircuitBreaker` (`ResilientPaymentGatewayAdapter`) は JVM
   プロセス内の状態 (直近の失敗回数など) を持つ。** Lambda の実行環境の使い捨て・並行実行の
   性質は、この種のプロセス内状態を前提にしたコンポーネントと相性が悪い。

## 帰結

**良い点**

- API サービス (長時間稼働の ECS サービス) とバッチ (ワンショットの ECS タスク) が、
  実行基盤のレイヤーでも対称的になる (`infra/terraform/modules/ecs-service` と
  `ecs-task` が同じ ECR イメージを指す)。CI/CD のビルド成果物が1種類で済む。
- Step Functions の `.waitForTaskToken` パターンと、`SettlementJobListener`/
  `SfnTaskCallbackAdapter` によるコールバックの設計は、ECS でも Lambda でもほぼ同じ形で
  成立する。もし将来 Lambda に切り替える判断をしても、この部分の設計変更は小さく済む
  (変わるのは「タスクをどう起動するか」の1点のみ)。

**トレードオフ・注意点**

- Fargate タスクの起動そのもの (コンテナイメージの pull、ENI のアタッチ等) にも数十秒
  程度のオーバーヘッドがある。1日1回程度の頻度で十分な消込バッチにおいてこの起動コストは
  問題にならないと判断しているが、「1分間隔で大量に細かいジョブを起動する」ようなユースケースに
  このテンプレートを転用する場合は、Lambda の方が適したケースもありうる (再検討の余地を残す)。
- ECS/Fargate は Lambda に比べて IAM ロール・ネットワーク設定 (VPC, セキュリティグループ) の
  構成要素が多く、`infra/terraform` の記述量もその分増えている。
