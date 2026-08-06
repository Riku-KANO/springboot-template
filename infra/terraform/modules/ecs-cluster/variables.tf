variable "name" {
  type = string
}

variable "container_insights_enabled" {
  type    = bool
  default = true
}

variable "fargate_spot_enabled" {
  description = "FARGATE_SPOT capacity provider を使うか。dev で費用を抑えたい場合に true にする (中断されうるため prod では非推奨)。"
  type        = bool
  default     = false
}

variable "tags" {
  type    = map(string)
  default = {}
}
