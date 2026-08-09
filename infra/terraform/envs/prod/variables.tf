# ============================================================================
# envs/prod の入力変数。
#
# 耐障害性・削除保護に関わる変数 (Multi-AZ, deletion_protection, skip_final_snapshot,
# FARGATE_SPOT の可否) はここに出さず main.tf に直接ハードコードする。
# 本番環境の安全装置を tfvars 経由で誰でも緩められる状態にしないため。
# ============================================================================

variable "aws_region" {
  type    = string
  default = "ap-northeast-1"
}

variable "account_id" {
  description = "デプロイ先 AWS アカウント ID (prod 専用アカウント)。実際の値は terraform.tfvars で必ず上書きすること。"
  type        = string
}

variable "availability_zones" {
  type    = list(string)
  default = ["ap-northeast-1a", "ap-northeast-1c"]
}

variable "vpc_cidr_block" {
  type    = string
  default = "10.30.0.0/16"
}

variable "public_subnet_cidrs" {
  type    = list(string)
  default = ["10.30.0.0/24", "10.30.1.0/24"]
}

variable "private_subnet_cidrs" {
  type    = list(string)
  default = ["10.30.10.0/24", "10.30.11.0/24"]
}

variable "rds_instance_class" {
  type    = string
  default = "db.r6g.large"
}

variable "rds_allocated_storage_gb" {
  type    = number
  default = 100
}

variable "rds_max_allocated_storage_gb" {
  type    = number
  default = 500
}

variable "rds_backup_retention_period_days" {
  description = "prod は監査・障害調査のため長めに保持する。"
  type        = number
  default     = 30
}

variable "ecs_api_cpu" {
  type    = number
  default = 1024
}

variable "ecs_api_memory" {
  type    = number
  default = 2048
}

variable "ecs_api_desired_count" {
  type    = number
  default = 3
}

variable "ecs_api_min_capacity" {
  description = "AZ 障害時にも最低限の可用性を保てるよう、2 AZ x 最低1タスクを上回る数を確保する。"
  type        = number
  default     = 3
}

variable "ecs_api_max_capacity" {
  type    = number
  default = 10
}

variable "ecs_api_cpu_target_utilization" {
  type    = number
  default = 60
}

variable "ecs_batch_cpu" {
  type    = number
  default = 1024
}

variable "ecs_batch_memory" {
  type    = number
  default = 2048
}

variable "log_retention_days" {
  description = "prod はコンプライアンス/障害調査のため長期保持する。"
  type        = number
  default     = 365
}

variable "settlement_bucket_name" {
  type    = string
  default = "template-settlements-prod"
}

variable "settlement_queue_name" {
  type    = string
  default = "template-settlement-tasks-prod"
}

variable "settlement_dispatch_pattern" {
  description = "prod は常に Pattern B (ECS RunTask) を使う。\"sqs\" もコード上は選べるが、実運用ではこの値を変えない想定。"
  type        = string
  default     = "ecs_run_task"

  validation {
    condition     = contains(["ecs_run_task", "sqs"], var.settlement_dispatch_pattern)
    error_message = "settlement_dispatch_pattern must be \"ecs_run_task\" or \"sqs\"."
  }
}

variable "github_repository" {
  type = string
}

variable "github_oidc_allowed_refs" {
  description = "prod への自動デプロイは main への push だけに限定する (stg のようなタグからの手動デプロイは許可しない)。"
  type        = list(string)
  default     = ["refs/heads/main"]
}

variable "terraform_state_bucket_name" {
  type    = string
  default = "template-terraform-state-EXAMPLE_ACCOUNT_ID"
}

variable "terraform_lock_table_name" {
  type    = string
  default = "template-terraform-locks"
}

variable "api_certificate_arn" {
  description = "API用ACM証明書ARN。prodではterraform.tfvarsで必ず設定する。"
  type        = string
}

variable "api_hosted_zone_id" {
  description = "APIドメインを管理するRoute 53 hosted zone ID。prodでは必須。"
  type        = string
}

variable "api_domain_name" {
  description = "公開APIのFQDN。prodでは必須。"
  type        = string
}

variable "alarm_email" {
  type     = string
  default  = null
  nullable = true
}
