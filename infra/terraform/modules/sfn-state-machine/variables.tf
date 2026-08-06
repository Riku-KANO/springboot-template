variable "name" {
  type = string
}

variable "definition" {
  description = "ASL (Amazon States Language) の JSON 文字列。envs/*/main.tf が templatefile(...) で statemachine/*.asl.json をレンダリングした結果を渡す。"
  type        = string
}

variable "role_arn" {
  description = "iam モジュールの sfn_execution_role_arn。"
  type        = string
}

variable "type" {
  description = "STANDARD (実行履歴が長期保持され .waitForTaskToken にも対応) か EXPRESS か。長時間の消込バッチを待つ本テンプレートの用途では STANDARD 一択。"
  type        = string
  default     = "STANDARD"
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "logging_level" {
  description = "ALL / ERROR / FATAL / OFF。"
  type        = string
  default     = "ERROR"
}

variable "tags" {
  type    = map(string)
  default = {}
}
