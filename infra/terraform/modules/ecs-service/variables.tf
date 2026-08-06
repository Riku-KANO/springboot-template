variable "name" {
  description = "サービス名/task definition family のプレフィックス (例: \"template-dev-api\")。"
  type        = string
}

variable "cluster_arn" {
  type = string
}

variable "image_uri" {
  description = "ECR のイメージ URI (タグ込み)。.github/workflows/docker-build-push.yml が push したタグを指す。"
  type        = string
}

variable "spring_profiles" {
  description = "SPRING_PROFILES_ACTIVE に渡すプロファイル (例: \"dev\")。batch は含めない (長時間稼働の Web サービスのため)。"
  type        = string
}

variable "cpu" {
  description = "Fargate タスクの CPU ユニット (例: 512 = 0.5 vCPU)。"
  type        = number
  default     = 512
}

variable "memory" {
  description = "Fargate タスクのメモリ (MiB)。"
  type        = number
  default     = 1024
}

variable "desired_count" {
  type    = number
  default = 1
}

variable "min_capacity" {
  type    = number
  default = 1
}

variable "max_capacity" {
  type    = number
  default = 1
}

variable "cpu_target_utilization" {
  description = "Application Auto Scaling が target-tracking で維持しようとする CPU 使用率 (%)。min_capacity < max_capacity のときのみ意味を持つ。"
  type        = number
  default     = 70
}

variable "subnet_ids" {
  type = list(string)
}

variable "security_group_ids" {
  type = list(string)
}

variable "assign_public_ip" {
  description = "private サブネットに置く前提 (NAT Gateway 経由でアウトバウンド) なので既定は false。"
  type        = bool
  default     = false
}

variable "target_group_arn" {
  description = "ALB のターゲットグループ ARN。null の場合は ALB 統合を行わない (スタンドアロン検証用)。"
  type        = string
  default     = null
}

variable "container_port" {
  type    = number
  default = 8080
}

variable "execution_role_arn" {
  type = string
}

variable "task_role_arn" {
  type = string
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "environment" {
  description = "コンテナに渡す平文の環境変数 (機密でない値のみ)。"
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "コンテナに渡す機密環境変数。キー = コンテナ内の環境変数名、値 = Secrets Manager/SSM の ARN。"
  type        = map(string)
  default     = {}
}

variable "region" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
