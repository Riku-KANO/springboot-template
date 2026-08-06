variable "name" {
  description = "task definition family 名 (例: \"template-dev-batch\")。"
  type        = string
}

variable "image_uri" {
  description = "ecs-service と同じ ECR イメージ URI (同一の bootJar を profile 違いで使い分ける)。"
  type        = string
}

variable "spring_profiles" {
  description = "SPRING_PROFILES_ACTIVE の既定値 (例: \"dev,batch\")。実際の settlementDate は Step Functions が RunTask の containerOverrides.command で渡す (このテンプレートのタスク定義自体は既定コマンドしか持たない)。"
  type        = string
}

variable "cpu" {
  type    = number
  default = 512
}

variable "memory" {
  type    = number
  default = 1024
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
  type    = map(string)
  default = {}
}

variable "secrets" {
  type    = map(string)
  default = {}
}

# subnet_ids / security_group_ids をこのモジュールが受け取らないのは意図的: ここで作るのは
# task definition (静的な定義) だけであり、実際にどの subnet/SG で起動するかは Step Functions の
# RunTask Parameters.NetworkConfiguration (statemachine/*.asl.json) が起動のたびに指定する。

variable "region" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
