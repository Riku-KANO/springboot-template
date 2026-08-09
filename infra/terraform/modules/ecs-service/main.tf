# ============================================================================
# ecs-service モジュール
#
# 長時間稼働の Web API (:bootstrap の bootJar を SPRING_PROFILES_ACTIVE=<env> で起動、
# batch プロファイルは含めない = Netty サーバーを立てる通常起動) を Fargate サービスとして動かす。
#
# 同じイメージが SettlementTaskListener (@SqsListener) / SfnTaskCallbackAdapter を常にコンポーネント
# スキャンで拾うため、このサービスは (Pattern A を選ぶ場合) settlement キューの待ち受けも兼ねる。
# batch プロファイルを付けないため spring.cloud.aws.sqs.listener.auto-startup は既定の true のまま。
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
      name      = "api"
      image     = var.image_uri
      essential = true

      portMappings = [
        {
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      environment = concat(
        [
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
          "awslogs-stream-prefix" = "api"
        }
      }

      # コンテナ内に curl/wget があることを前提にしない。readiness は ALB の
      # /actuator/health/readiness health check で判定する。
    }
  ])

  tags = var.tags
}

resource "aws_ecs_service" "this" {
  name            = var.name
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = var.assign_public_ip
  }

  dynamic "load_balancer" {
    for_each = var.target_group_arn != null ? [var.target_group_arn] : []
    content {
      target_group_arn = load_balancer.value
      container_name   = "api"
      container_port   = var.container_port
    }
  }

  # デプロイ中に旧タスクを先に落としてから新タスクを起動する構成 (最小容量が保証しにくい dev)
  # にはしない。常に新タスクの healthy 確認後に旧タスクを落とす (Rolling update, 既定の
  # deployment_minimum_healthy_percent=100 / maximum_percent=200 のまま)。

  lifecycle {
    ignore_changes = [
      # Terraform はサービスの土台、CI は SHA 固定の task definition revision を管理する。
      # apply のたびに CI がデプロイした revision を :latest へ戻さない。
      task_definition,
    ]
  }

  tags = var.tags
}

resource "aws_appautoscaling_target" "this" {
  count = var.max_capacity > var.min_capacity ? 1 : 0

  max_capacity       = var.max_capacity
  min_capacity       = var.min_capacity
  resource_id        = "service/${split("/", var.cluster_arn)[1]}/${aws_ecs_service.this.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "cpu" {
  count = var.max_capacity > var.min_capacity ? 1 : 0

  name               = "${var.name}-cpu-target-tracking"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.this[0].resource_id
  scalable_dimension = aws_appautoscaling_target.this[0].scalable_dimension
  service_namespace  = aws_appautoscaling_target.this[0].service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value = var.cpu_target_utilization
  }
}
