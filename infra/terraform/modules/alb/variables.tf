variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "ALBを配置するpublic subnet IDs。"
  type        = list(string)
}

variable "security_group_ids" {
  type = list(string)
}

variable "container_port" {
  type    = number
  default = 8080
}

variable "deletion_protection" {
  description = "ALBの削除保護。prodではtrueにする。"
  type        = bool
  default     = false
}

variable "certificate_arn" {
  description = "ACM証明書ARN。nullの場合はdev向けにHTTP listenerで直接forwardする。"
  type        = string
  default     = null
  nullable    = true
}

variable "hosted_zone_id" {
  description = "Route 53 hosted zone ID。domain_nameと両方指定した場合だけAliasレコードを作る。"
  type        = string
  default     = null
  nullable    = true
}

variable "domain_name" {
  description = "APIのFQDN。hosted_zone_idと両方指定した場合だけAliasレコードを作る。"
  type        = string
  default     = null
  nullable    = true
}

variable "tags" {
  type    = map(string)
  default = {}
}
