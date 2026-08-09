variable "name" {
  description = "ロール名のプレフィックス (例: \"template-dev\")。"
  type        = string
}

variable "region" {
  type = string
}

variable "account_id" {
  type = string
}

variable "db_secret_arn" {
  description = "RDS が管理する (あるいはアプリ用に別途作った) DB 認証情報シークレットの ARN。ECS タスク実行ロール (secrets 経由の env 注入) とタスクロール (spring.config.import 経由のランタイム読み込み) の両方が読む。"
  type        = string
}

variable "ssm_parameter_path_arn" {
  description = "spring.config.import の aws-parameterstore:/template/{env}/ が読みに行く SSM パラメータ配下を指す ARN パターン (例: arn:aws:ssm:REGION:ACCOUNT:parameter/template/dev/*)。"
  type        = string
}

variable "settlement_bucket_arn" {
  description = "消込ファイル用 S3 バケットの ARN (s3-bucket モジュールの bucket_arn 出力)。"
  type        = string
}

variable "settlement_queue_arn" {
  description = "Pattern A (SQS waitForTaskToken) 用キューの ARN。"
  type        = string
}

variable "ecs_task_definition_arn_pattern" {
  description = <<-EOT
    Step Functions が ecs:RunTask を許可する対象の task definition ARN パターン
    (リビジョン番号を含まないファミリ ARN。例: arn:aws:ecs:REGION:ACCOUNT:task-definition/template-dev-batch)。
    デプロイの都度リビジョンが上がるため、末尾にリビジョン番号を付けたピン留めはしない。
  EOT
  type        = string
}

variable "create_github_oidc_provider" {
  description = <<-EOT
    GitHub Actions 用の OIDC プロバイダ (token.actions.githubusercontent.com) を作るかどうか。
    OIDC プロバイダは AWS アカウントに1つあれば全環境 (dev/stg/prod) で共有できるリソースであり、
    3つの env root module がそれぞれ作ろうとすると "provider already exists" で衝突する。
    運用上は dev の terraform.tfvars だけで true にし、stg/prod は
    existing_github_oidc_provider_arn 変数で dev が作った ARN を参照する、という
    「1箇所だけが所有する」運用を想定する。
  EOT
  type        = bool
  default     = false
}

variable "existing_github_oidc_provider_arn" {
  description = "create_github_oidc_provider = false のときに参照する、既存の GitHub OIDC プロバイダの ARN。"
  type        = string
  default     = null
}

variable "github_repository" {
  description = "OIDC の信頼ポリシーを縛る \"org/repo\" 形式のリポジトリ名 (例: \"your-org/springboot-template\")。"
  type        = string
}

variable "github_oidc_allowed_refs" {
  description = "OIDC トークンの sub クレームで許可する ref のリスト (例: [\"refs/heads/main\"])。ここに無い ref からのワークフローはロールを引き受けられない。"
  type        = list(string)
  default     = ["refs/heads/main"]
}

variable "github_oidc_allowed_environments" {
  description = "GitHub Environment 名のリスト。environment を指定した job の OIDC sub は ref ではなく environment になるため、デプロイ先だけを明示的に許可する。"
  type        = list(string)
  default     = []
}

variable "ecr_repository_arn" {
  description = "GitHub Actions が push する ECR リポジトリの ARN。"
  type        = string
}

variable "terraform_state_bucket_arn" {
  description = "Terraform plan roleがstateを読むS3 bucket ARN。"
  type        = string
}

variable "terraform_lock_table_arn" {
  description = "Terraform plan roleがlockを取得するDynamoDB table ARN。"
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
