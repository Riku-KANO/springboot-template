# ============================================================================
# リモートステート (S3 + DynamoDB ロック)。
#
# バケット/テーブルはこの Terraform 自身の管理対象外 (state を保存する場所を state 管理下に
# 置くと「最初の apply で自分の置き場所を作る」という鶏と卵になるため)。事前に1回だけ
# 手動 (あるいは別の使い捨て Terraform 構成) で用意しておくこと:
#
#   aws s3api create-bucket --bucket template-terraform-state-<account-id> \
#     --region ap-northeast-1 --create-bucket-configuration LocationConstraint=ap-northeast-1
#   aws s3api put-bucket-versioning --bucket template-terraform-state-<account-id> \
#     --versioning-configuration Status=Enabled
#   aws dynamodb create-table --table-name template-terraform-locks \
#     --attribute-definitions AttributeName=LockID,AttributeType=S \
#     --key-schema AttributeName=LockID,KeyType=HASH \
#     --billing-mode PAY_PER_REQUEST
#
# bucket 名はグローバルに一意である必要があるため、<account-id> 部分を実際のアカウント ID に
# 置き換えること (このファイルの値もそれに合わせて書き換える)。
#
# `terraform init -backend=false` で検証する分には、この backend 自体の実在は問われない
# (Definition of Done: docs 参照)。
# ============================================================================

terraform {
  backend "s3" {
    bucket         = "template-terraform-state-EXAMPLE_ACCOUNT_ID"
    key            = "envs/dev/terraform.tfstate"
    region         = "ap-northeast-1"
    dynamodb_table = "template-terraform-locks"
    encrypt        = true
  }
}
