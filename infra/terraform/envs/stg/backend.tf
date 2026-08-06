# backend の用意手順は envs/dev/backend.tf のコメントを参照 (bucket/table 名は共有し、
# key だけ環境ごとに変える運用を想定)。
terraform {
  backend "s3" {
    bucket         = "template-terraform-state-EXAMPLE_ACCOUNT_ID"
    key            = "envs/stg/terraform.tfstate"
    region         = "ap-northeast-1"
    dynamodb_table = "template-terraform-locks"
    encrypt        = true
  }
}
