variable "name" {
  description = "DB インスタンス識別子のプレフィックス (例: \"template-dev\")。"
  type        = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "DB サブネットグループに使う private サブネット ID のリスト。"
  type        = list(string)
}

variable "allowed_security_group_ids" {
  description = "5432 番ポートへの到達を許可するセキュリティグループ (network モジュールの ecs_service/ecs_task の SG)。"
  type        = list(string)
}

variable "db_name" {
  description = "アプリが接続する論理データベース名。bootstrap の application-{env}.yml の spring.datasource.url / spring.r2dbc.url のパス部分と一致させること。"
  type        = string
  default     = "template"
}

variable "master_username" {
  type    = string
  default = "template_admin"
}

variable "master_password" {
  description = "マスターユーザーのパスワード。envs/*/main.tf が生成し (random_password)、同じ値を template/{env}/db-credentials という名前の Secrets Manager シークレットにも書き込む (アプリの spring.config.import が読む先と一致させるため)。"
  type        = string
  sensitive   = true
}

variable "engine_version" {
  description = "PostgreSQL のメジャー.マイナーバージョン。docker-compose.yml のローカル Postgres (postgres:17-alpine) と揃えておくと、ローカル/実環境の SQL 方言差異を気にしなくてよい。"
  type        = string
  default     = "17.4"
}

variable "instance_class" {
  type = string
}

variable "allocated_storage_gb" {
  type    = number
  default = 20
}

variable "max_allocated_storage_gb" {
  description = "ストレージオートスケーリングの上限。allocated_storage_gb 以下を指定すると自動スケーリングを無効化する。"
  type        = number
}

variable "multi_az" {
  description = "Multi-AZ 配置にするか。prod は true 必須、dev/stg はコスト優先で false にすることを想定。"
  type        = bool
  default     = false
}

variable "backup_retention_period_days" {
  type    = number
  default = 7
}

variable "deletion_protection" {
  description = "誤った terraform destroy / apply による削除を防ぐ。prod では必ず true にする。"
  type        = bool
  default     = false
}

variable "skip_final_snapshot" {
  description = "削除時に最終スナップショットを取らずに即削除するか。dev は true (使い捨て) 、stg/prod は false (最終スナップショットを残す) を想定。"
  type        = bool
  default     = true
}

variable "performance_insights_enabled" {
  type    = bool
  default = false
}

variable "apply_immediately" {
  description = "変更を即時適用するか。prod では false にし、メンテナンスウィンドウでの適用に倒す方が安全。"
  type        = bool
  default     = true
}

variable "tags" {
  type    = map(string)
  default = {}
}
