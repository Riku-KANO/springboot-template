variable "name" {
  description = "リソース名のプレフィックスとして使う識別子 (例: \"template-dev\")。"
  type        = string
}

variable "vpc_cidr_block" {
  description = "VPC 全体の CIDR ブロック。"
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "サブネットを配置する AZ のリスト。要素数だけ public/private サブネットが作られる。"
  type        = list(string)
}

variable "public_subnet_cidrs" {
  description = "public サブネットの CIDR ブロック (availability_zones と同じ要素数・同じ並び順で対応させる)。"
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "private サブネットの CIDR ブロック (availability_zones と同じ要素数・同じ並び順で対応させる)。"
  type        = list(string)
}

variable "enable_nat_gateway" {
  description = <<-EOT
    private サブネットからインターネット (ECR pull, Secrets Manager/SSM/CloudWatch Logs 呼び出し等) へ
    出て行くための NAT Gateway を作るかどうか。NAT Gateway は時間課金 + データ処理課金が発生するため、
    dev では単一 NAT (コスト優先)、stg/prod では AZ ごとに NAT を持たせて可用性を優先する、
    といった環境差を single_nat_gateway で表現する。
  EOT
  type        = bool
  default     = true
}

variable "single_nat_gateway" {
  description = "true の場合 NAT Gateway を1つだけ作り全 private サブネットで共有する (dev 向けのコスト最適化)。false の場合は AZ ごとに1つずつ作る (stg/prod 向けの可用性優先)。"
  type        = bool
  default     = true
}

variable "tags" {
  description = "全リソース共通で付与するタグ。"
  type        = map(string)
  default     = {}
}
