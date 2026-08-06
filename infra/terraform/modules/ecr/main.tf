# ============================================================================
# ecr モジュール
#
# :bootstrap が生成する bootJar (bootBuildImage で作る OCI イメージ、
# .github/workflows/docker-build-push.yml が push する) の格納先。
# ECS サービス (長時間稼働の API) と ECS の一発バッチタスクは同じイメージを
# 異なる起動コマンド (spring.profiles.active) で使い分けるため、リポジトリは1つで足りる。
# ============================================================================

resource "aws_ecr_repository" "this" {
  name                 = var.name
  image_tag_mutability = var.image_tag_mutability

  image_scanning_configuration {
    scan_on_push = var.scan_on_push
  }

  tags = var.tags
}

resource "aws_ecr_lifecycle_policy" "this" {
  count      = var.untagged_image_expiry_days > 0 ? 1 : 0
  repository = aws_ecr_repository.this.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "タグ無しイメージを ${var.untagged_image_expiry_days} 日で失効させる"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = var.untagged_image_expiry_days
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}
