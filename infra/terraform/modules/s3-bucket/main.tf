# ============================================================================
# s3-bucket モジュール
#
# 決済プロバイダの日次消込ファイル (settlements/{yyyy-MM-dd}.csv) の格納先。
# adapter-messaging の S3SettlementFileAdapter が settlement.s3.bucket-name /
# settlement.s3.key-prefix (既定値 "settlements") で参照する実体がこれ。
# ローカルでは docker/localstack-init/init-aws.sh が LocalStack 上に同名の構成を作る。
# ============================================================================

resource "aws_s3_bucket" "this" {
  bucket        = var.bucket_name
  force_destroy = var.force_destroy

  tags = var.tags
}

resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id
  versioning_configuration {
    status = var.versioning_enabled ? "Enabled" : "Suspended"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  count  = var.versioning_enabled ? 1 : 0
  bucket = aws_s3_bucket.this.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_expiration_days
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}
