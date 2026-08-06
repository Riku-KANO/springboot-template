# ============================================================================
# ecs-cluster モジュール
#
# 長時間稼働の API (ecs-service モジュール) とワンショットの消込バッチタスク (ecs-task モジュール、
# Step Functions の "Pattern B" ecs:runTask.waitForTaskToken から起動される) の両方を、
# 同じ ECS クラスタ上で Fargate 起動タイプとして動かす。
# ============================================================================

resource "aws_ecs_cluster" "this" {
  name = var.name

  setting {
    name  = "containerInsights"
    value = var.container_insights_enabled ? "enabled" : "disabled"
  }

  tags = var.tags
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name = aws_ecs_cluster.this.name

  capacity_providers = var.fargate_spot_enabled ? ["FARGATE", "FARGATE_SPOT"] : ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
    base              = 1
  }
}
