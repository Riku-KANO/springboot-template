variable "name" { type = string }
variable "alarm_topic_arn" { type = string }
variable "alarm_email" {
  description = "通知先メール。nullならSNS topicだけを作り、購読は利用側で設定する。"
  type        = string
  default     = null
  nullable    = true
}
variable "alb_arn_suffix" { type = string }
variable "target_group_arn_suffix" { type = string }
variable "ecs_cluster_name" { type = string }
variable "ecs_service_name" { type = string }
variable "rds_instance_id" { type = string }
variable "sqs_dlq_name" {
  type     = string
  default  = null
  nullable = true
}
variable "tags" {
  type    = map(string)
  default = {}
}
