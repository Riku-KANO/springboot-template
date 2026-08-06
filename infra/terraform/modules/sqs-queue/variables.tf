variable "name" {
  description = "SQS キュー名。settlement.sqs.queue-name (application-{env}.yml) と必ず一致させること。"
  type        = string
}

variable "visibility_timeout_seconds" {
  description = <<-EOT
    可視性タイムアウト。SettlementTaskListener (@SqsListener) がメッセージを受け取ってから
    settlementReconciliationJob を起動し JobOperator.start が返るまでの間 (ジョブの起動シグナル
    送信のみで、ジョブ完了そのものは待たない) をカバーできれば十分だが、余裕を持って長めに取る。
  EOT
  type        = number
  default     = 60
}

variable "message_retention_seconds" {
  type    = number
  default = 345600 # 4日
}

variable "enable_dlq" {
  description = "デッドレターキューを作るか。処理に繰り返し失敗したメッセージ (壊れた taskToken 等) を隔離するために推奨。"
  type        = bool
  default     = true
}

variable "max_receive_count" {
  description = "DLQ に送られるまでにメイン キューでの受信を許す回数。"
  type        = number
  default     = 5
}

variable "tags" {
  type    = map(string)
  default = {}
}
