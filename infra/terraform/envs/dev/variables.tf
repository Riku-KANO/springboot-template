# ============================================================================
# envs/dev の入力変数。
#
# 値そのものは terraform.tfvars に置く (このファイルは型・説明・既定値だけを定義する)。
# stg/prod にも同名の variables.tf があるが、既定値やコメントは環境ごとの実情に合わせて
# 意図的に変えてある (単純なコピーではない)。
# ============================================================================

variable "aws_region" {
  type    = string
  default = "ap-northeast-1"
}

variable "account_id" {
  description = "デプロイ先 AWS アカウント ID。IAM ポリシーの ARN 組み立てに使う (実際の値は terraform.tfvars で必ず上書きすること。ここにはプレースホルダしか置かない)。"
  type        = string
}

variable "availability_zones" {
  type    = list(string)
  default = ["ap-northeast-1a", "ap-northeast-1c"]
}

variable "vpc_cidr_block" {
  type    = string
  default = "10.10.0.0/16"
}

variable "public_subnet_cidrs" {
  type    = list(string)
  default = ["10.10.0.0/24", "10.10.1.0/24"]
}

variable "private_subnet_cidrs" {
  type    = list(string)
  default = ["10.10.10.0/24", "10.10.11.0/24"]
}

variable "single_nat_gateway" {
  description = "dev はコスト優先で NAT Gateway を1つだけにする。"
  type        = bool
  default     = true
}

variable "rds_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "rds_allocated_storage_gb" {
  type    = number
  default = 20
}

variable "rds_max_allocated_storage_gb" {
  type    = number
  default = 50
}

variable "rds_multi_az" {
  description = "dev は Multi-AZ 不要 (コスト優先、可用性より復旧の速さより低コストを取る)。"
  type        = bool
  default     = false
}

variable "rds_deletion_protection" {
  description = "dev は作り直しが前提の使い捨て環境なので削除保護は不要。"
  type        = bool
  default     = false
}

variable "rds_skip_final_snapshot" {
  type    = bool
  default = true
}

variable "rds_backup_retention_period_days" {
  type    = number
  default = 1
}

variable "ecs_api_cpu" {
  type    = number
  default = 256
}

variable "ecs_api_memory" {
  type    = number
  default = 512
}

variable "ecs_api_desired_count" {
  type    = number
  default = 1
}

variable "ecs_api_min_capacity" {
  type    = number
  default = 1
}

variable "ecs_api_max_capacity" {
  description = "dev はオートスケーリング不要 (min == max なら ecs-service モジュールが Application Auto Scaling 自体を作らない)。"
  type        = number
  default     = 1
}

variable "ecs_batch_cpu" {
  type    = number
  default = 512
}

variable "ecs_batch_memory" {
  type    = number
  default = 1024
}

variable "fargate_spot_enabled" {
  description = "dev はワンショットバッチ・API ともに中断されても実害が小さいため SPOT を許容してコストを下げる。"
  type        = bool
  default     = true
}

variable "log_retention_days" {
  type    = number
  default = 7
}

variable "settlement_bucket_name" {
  description = "S3 バケット名はグローバルに一意である必要がある。既定値のまま複数アカウントで使うと衝突するため、必ず一意な値に変更すること。"
  type        = string
  default     = "template-settlements-dev"
}

variable "settlement_bucket_force_destroy" {
  description = "dev は使い捨てなので destroy 時にオブジェクトが残っていても強制削除してよい。"
  type        = bool
  default     = true
}

variable "settlement_queue_name" {
  type    = string
  default = "template-settlement-tasks-dev"
}

variable "settlement_dispatch_pattern" {
  description = "\"ecs_run_task\" (Pattern B, 既定) か \"sqs\" (Pattern A) か。dev でもあえて本番と同じ Pattern B を既定にし、ECS RunTask 統合をステージング前に検証できるようにしている。"
  type        = string
  default     = "ecs_run_task"

  validation {
    condition     = contains(["ecs_run_task", "sqs"], var.settlement_dispatch_pattern)
    error_message = "settlement_dispatch_pattern must be \"ecs_run_task\" or \"sqs\"."
  }
}

variable "github_repository" {
  description = "\"org/repo\" 形式。GitHub Actions OIDC の信頼ポリシーをこのリポジトリだけに絞る。"
  type        = string
}

variable "github_oidc_allowed_refs" {
  type    = list(string)
  default = ["refs/heads/main"]
}

variable "create_github_oidc_provider" {
  description = "GitHub OIDC プロバイダは AWS アカウントに1つで足りる共有リソース。dev の terraform.tfvars だけ true にし、stg/prod は false のまま (account_id から同じ ARN を導出して参照する)。"
  type        = bool
  default     = true
}
