variable "bucket_name" {
  description = "S3 バケット名 (グローバルに一意である必要がある)。settlement.s3.bucket-name (application-{env}.yml) と必ず一致させること。"
  type        = string
}

variable "versioning_enabled" {
  type    = bool
  default = true
}

variable "force_destroy" {
  description = "true の場合、バケットが空でなくても terraform destroy で削除できる。dev のみ true を想定。"
  type        = bool
  default     = false
}

variable "noncurrent_version_expiration_days" {
  description = "versioning_enabled のとき、非最新バージョンを何日で自動削除するか。"
  type        = number
  default     = 90
}

variable "tags" {
  type    = map(string)
  default = {}
}
