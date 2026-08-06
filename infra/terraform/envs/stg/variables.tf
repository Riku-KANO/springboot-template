# ============================================================================
# envs/stg の入力変数。
#
# application-stg.yml のコメントにある通り、stg は「本番相当 (prod-shaped) のステージング」
# であり、dev のようなコスト最優先の割り切りはしない。一方で prod ほどの耐障害性
# (Multi-AZ の RDS 等) までは求めない、という dev と prod の中間の位置づけ。
# ============================================================================

variable "aws_region" {
  type    = string
  default = "ap-northeast-1"
}

variable "account_id" {
  description = "デプロイ先 AWS アカウント ID (stg 用アカウント)。実際の値は terraform.tfvars で必ず上書きすること。"
  type        = string
}

variable "availability_zones" {
  type    = list(string)
  default = ["ap-northeast-1a", "ap-northeast-1c"]
}

variable "vpc_cidr_block" {
  type    = string
  default = "10.20.0.0/16"
}

variable "public_subnet_cidrs" {
  type    = list(string)
  default = ["10.20.0.0/24", "10.20.1.0/24"]
}

variable "private_subnet_cidrs" {
  type    = list(string)
  default = ["10.20.10.0/24", "10.20.11.0/24"]
}

variable "single_nat_gateway" {
  description = "stg は本番同様に AZ ごとの NAT Gateway を持たせ、片系 AZ 障害時の挙動を prod より前に検証する。"
  type        = bool
  default     = false
}

variable "rds_instance_class" {
  type    = string
  default = "db.t4g.small"
}

variable "rds_allocated_storage_gb" {
  type    = number
  default = 50
}

variable "rds_max_allocated_storage_gb" {
  type    = number
  default = 200
}

variable "rds_backup_retention_period_days" {
  type    = number
  default = 7
}

variable "ecs_api_cpu" {
  type    = number
  default = 512
}

variable "ecs_api_memory" {
  type    = number
  default = 1024
}

variable "ecs_api_desired_count" {
  type    = number
  default = 2
}

variable "ecs_api_min_capacity" {
  type    = number
  default = 2
}

variable "ecs_api_max_capacity" {
  description = "prod へのリリース前にオートスケーリングの挙動そのものを検証したいので、stg でも min < max にしてオートスケーリングを有効にしておく。"
  type        = number
  default     = 4
}

variable "ecs_batch_cpu" {
  type    = number
  default = 512
}

variable "ecs_batch_memory" {
  type    = number
  default = 1024
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "settlement_bucket_name" {
  type    = string
  default = "template-settlements-stg"
}

variable "settlement_queue_name" {
  type    = string
  default = "template-settlement-tasks-stg"
}

variable "settlement_dispatch_pattern" {
  type    = string
  default = "ecs_run_task"

  validation {
    condition     = contains(["ecs_run_task", "sqs"], var.settlement_dispatch_pattern)
    error_message = "settlement_dispatch_pattern must be \"ecs_run_task\" or \"sqs\"."
  }
}

variable "github_repository" {
  type = string
}

variable "github_oidc_allowed_refs" {
  description = "stg は main ブランチからの自動デプロイに加え、タグ付きリリースからの手動デプロイも許可する運用を想定。"
  type        = list(string)
  default     = ["refs/heads/main", "refs/tags/*"]
}
