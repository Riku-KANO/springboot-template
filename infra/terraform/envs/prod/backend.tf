# backend の用意手順は envs/dev/backend.tf のコメントを参照。
terraform {
  backend "s3" {
    bucket         = "template-terraform-state-EXAMPLE_ACCOUNT_ID"
    key            = "envs/prod/terraform.tfstate"
    region         = "ap-northeast-1"
    dynamodb_table = "template-terraform-locks"
    encrypt        = true
  }
}
