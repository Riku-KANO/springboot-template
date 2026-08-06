# ============================================================================
# ecs-task モジュール
#
# settlementReconciliationJob を1回実行して終了する、ワンショット ECS タスクの定義
# ("Pattern B": statemachine/settlement-reconciliation.asl.json が
# arn:aws:states:::ecs:runTask.waitForTaskToken でこのタスク定義を起動する)。
#
# aws_ecs_service を作らないのがこのモジュールと ecs-service モジュールの唯一かつ最大の違い。
# ここで登録するのは task definition だけであり、実際の起動 (RunTask) は Step Functions が
# 実行のたびに行う。settlementDate と taskToken は Step Functions 側の
# Overrides.ContainerOverrides[].Command (ASL 定義側、本 Terraform モジュールの管轄外) が
# 実行時に渡すため、ここでは command を一切ハードコードしない
# (:bootstrap の application-batch.yml が期待する
# "--spring.profiles.active=<env>,batch --spring.batch.job.name=settlementReconciliationJob
#  settlementDate=<date> taskToken=<token>" という非オプション引数混じりのコマンドラインを、
# ASL 側が実行のたびに組み立てる)。
# ============================================================================

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${var.name}"
  retention_in_days = var.log_retention_days

  tags = var.tags
}

resource "aws_ecs_task_definition" "this" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.cpu)
  memory                   = tostring(var.memory)
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  container_definitions = jsonencode([
    {
      name      = "batch"
      image     = var.image_uri
      essential = true
      # command は意図的に指定しない。実際に何をどう実行するか (spring.profiles.active,
      # spring.batch.job.name, settlementDate, taskToken) は Step Functions の
      # ContainerOverrides が実行のたびに渡す。

      environment = concat(
        [
          # SPRING_MAIN_WEB_APPLICATION_TYPE 等、application-batch.yml が既に決め打ちしている
          # 設定はここで重複させない。あくまでフォールバックの既定プロファイルだけを持たせる。
          { name = "SPRING_PROFILES_ACTIVE", value = var.spring_profiles },
        ],
        [for k, v in var.environment : { name = k, value = v }]
      )

      secrets = [for k, v in var.secrets : { name = k, valueFrom = v }]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "batch"
        }
      }
    }
  ])

  tags = var.tags
}
