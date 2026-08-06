variable "name" {
  description = "ECR リポジトリ名 (例: \"template-dev\")。bootJar のイメージを push する先。"
  type        = string
}

variable "image_tag_mutability" {
  description = "IMMUTABLE にすると同一タグへの再 push を禁止できる。stg/prod では IMMUTABLE を推奨 (誤って既存タグを上書きデプロイする事故を防ぐ)。"
  type        = string
  default     = "MUTABLE"
}

variable "scan_on_push" {
  description = "push 時に ECR の脆弱性スキャンを走らせるか。"
  type        = bool
  default     = true
}

variable "untagged_image_expiry_days" {
  description = "タグ無し (dangling) イメージを何日で自動削除するか。0 を指定すると失効ポリシーを作らない。"
  type        = number
  default     = 7
}

variable "tags" {
  type    = map(string)
  default = {}
}
