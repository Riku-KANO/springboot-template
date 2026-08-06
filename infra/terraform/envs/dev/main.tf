# ============================================================================
# envs/dev - 開発環境のルートモジュール
#
# 実際の AWS (dev アカウント) に、application-dev.yml が前提とする構成一式
# (VPC, RDS PostgreSQL, ECR, ECS Fargate の API サービス + 消込バッチのワンショットタスク,
# S3 (消込ファイル), SQS (Pattern A 用), Step Functions ステートマシン, IAM) を作る。
#
# dev はコスト最優先: 単一 NAT Gateway, db.t4g.micro, Multi-AZ 無し, FARGATE_SPOT 許容,
# 削除保護なし。stg/prod との違いは terraform.tfvars の値と、このファイル内の一部の
# ハードコードされたガードレール (prod の deletion_protection 強制など) で表現する。
# ============================================================================

locals {
  env_name = "dev"
  name     = "template-${local.env_name}"

  common_tags = {
    Project     = "springboot-template"
    Environment = local.env_name
    ManagedBy   = "terraform"
  }

  batch_task_family = "${local.name}-batch"

  # GitHub OIDC プロバイダの ARN は AWS アカウントごとに一意に決まる (作成者に関わらず
  # 同じ ARN になる) ため、dev がこの環境で新規作成する場合も他 env が既存参照する場合も
  # 同じ式で導出できる。
  github_oidc_provider_arn = "arn:aws:iam::${var.account_id}:oidc-provider/token.actions.githubusercontent.com"

  ecs_task_definition_arn_pattern = "arn:aws:ecs:${var.aws_region}:${var.account_id}:task-definition/${local.batch_task_family}"

  asl_vars_ecs_run_task = {
    cluster_arn                = module.ecs_cluster.cluster_arn
    task_definition_arn        = module.ecs_task_batch.task_definition_arn
    container_name             = module.ecs_task_batch.container_name
    private_subnet_id_1        = module.network.private_subnet_ids[0]
    private_subnet_id_2        = module.network.private_subnet_ids[1]
    ecs_task_security_group_id = module.network.ecs_task_security_group_id
    spring_profiles            = "${local.env_name},batch"
    sns_topic_arn              = aws_sns_topic.settlement_notifications.arn
  }

  asl_vars_sqs = {
    sqs_queue_url = module.sqs_settlement_tasks.queue_url
    sns_topic_arn = aws_sns_topic.settlement_notifications.arn
  }

  asl_definition = (
    var.settlement_dispatch_pattern == "sqs"
    ? templatefile("${path.module}/../../statemachine/settlement-reconciliation-sqs.asl.json", local.asl_vars_sqs)
    : templatefile("${path.module}/../../statemachine/settlement-reconciliation.asl.json", local.asl_vars_ecs_run_task)
  )
}

module "network" {
  source = "../../modules/network"

  name                 = local.name
  vpc_cidr_block       = var.vpc_cidr_block
  availability_zones   = var.availability_zones
  public_subnet_cidrs  = var.public_subnet_cidrs
  private_subnet_cidrs = var.private_subnet_cidrs
  enable_nat_gateway   = true
  single_nat_gateway   = var.single_nat_gateway
  tags                 = local.common_tags
}

module "ecr" {
  source = "../../modules/ecr"

  name                       = local.name
  image_tag_mutability       = "MUTABLE" # dev は同一タグへの再 push (例: "latest") を許容する
  untagged_image_expiry_days = 7
  tags                       = local.common_tags
}

module "s3_settlements" {
  source = "../../modules/s3-bucket"

  bucket_name        = var.settlement_bucket_name
  versioning_enabled = true
  force_destroy      = var.settlement_bucket_force_destroy
  tags               = local.common_tags
}

module "sqs_settlement_tasks" {
  source = "../../modules/sqs-queue"

  name = var.settlement_queue_name
  tags = local.common_tags
}

module "ecs_cluster" {
  source = "../../modules/ecs-cluster"

  name                       = local.name
  container_insights_enabled = true
  fargate_spot_enabled       = var.fargate_spot_enabled
  tags                       = local.common_tags
}

# --- DB 認証情報 ---
# application-dev.yml の spring.config.import が読みに行く
# "aws-secretsmanager:template/dev/db-credentials" と完全に同じ名前・同じ JSON 形状
# ({"username": ..., "password": ...}) でシークレットを作る。

resource "random_password" "db" {
  length  = 32
  special = false # JDBC URL や環境変数への埋め込みで問題を起こしうる記号を避ける
}

resource "aws_secretsmanager_secret" "db_credentials" {
  name = "template/${local.env_name}/db-credentials"
  tags = local.common_tags
}

resource "aws_secretsmanager_secret_version" "db_credentials" {
  secret_id = aws_secretsmanager_secret.db_credentials.id
  secret_string = jsonencode({
    username = "template_admin"
    password = random_password.db.result
  })
}

module "rds" {
  source = "../../modules/rds-postgres"

  name                         = local.name
  vpc_id                       = module.network.vpc_id
  subnet_ids                   = module.network.private_subnet_ids
  allowed_security_group_ids   = [module.network.rds_security_group_id]
  master_username              = "template_admin"
  master_password              = random_password.db.result
  instance_class               = var.rds_instance_class
  allocated_storage_gb         = var.rds_allocated_storage_gb
  max_allocated_storage_gb     = var.rds_max_allocated_storage_gb
  multi_az                     = var.rds_multi_az
  deletion_protection          = var.rds_deletion_protection
  skip_final_snapshot          = var.rds_skip_final_snapshot
  backup_retention_period_days = var.rds_backup_retention_period_days
  apply_immediately            = true
  tags                         = local.common_tags
}

module "iam" {
  source = "../../modules/iam"

  name                              = local.name
  region                            = var.aws_region
  account_id                        = var.account_id
  db_secret_arn                     = aws_secretsmanager_secret.db_credentials.arn
  ssm_parameter_path_arn            = "arn:aws:ssm:${var.aws_region}:${var.account_id}:parameter/template/${local.env_name}/*"
  settlement_bucket_arn             = module.s3_settlements.bucket_arn
  settlement_queue_arn              = module.sqs_settlement_tasks.queue_arn
  ecs_task_definition_arn_pattern   = local.ecs_task_definition_arn_pattern
  create_github_oidc_provider       = var.create_github_oidc_provider
  existing_github_oidc_provider_arn = local.github_oidc_provider_arn
  github_repository                 = var.github_repository
  github_oidc_allowed_refs          = var.github_oidc_allowed_refs
  ecr_repository_arn                = module.ecr.repository_arn
  tags                              = local.common_tags
}

module "ecs_task_batch" {
  source = "../../modules/ecs-task"

  name               = local.batch_task_family
  image_uri          = "${module.ecr.repository_url}:latest"
  spring_profiles    = "${local.env_name},batch"
  cpu                = var.ecs_batch_cpu
  memory             = var.ecs_batch_memory
  execution_role_arn = module.iam.ecs_task_execution_role_arn
  task_role_arn      = module.iam.ecs_batch_task_role_arn
  log_retention_days = var.log_retention_days
  region             = var.aws_region
  tags               = local.common_tags
}

module "ecs_service_api" {
  source = "../../modules/ecs-service"

  name               = "${local.name}-api"
  cluster_arn        = module.ecs_cluster.cluster_arn
  image_uri          = "${module.ecr.repository_url}:latest"
  spring_profiles    = local.env_name
  cpu                = var.ecs_api_cpu
  memory             = var.ecs_api_memory
  desired_count      = var.ecs_api_desired_count
  min_capacity       = var.ecs_api_min_capacity
  max_capacity       = var.ecs_api_max_capacity
  subnet_ids         = module.network.private_subnet_ids
  security_group_ids = [module.network.ecs_service_security_group_id]
  assign_public_ip   = false
  execution_role_arn = module.iam.ecs_task_execution_role_arn
  task_role_arn      = module.iam.ecs_api_task_role_arn
  log_retention_days = var.log_retention_days
  region             = var.aws_region
  tags               = local.common_tags
}

resource "aws_sns_topic" "settlement_notifications" {
  name = "${local.name}-settlement-notifications"
  tags = local.common_tags
}

module "sfn_settlement_reconciliation" {
  source = "../../modules/sfn-state-machine"

  name               = "${local.name}-settlement-reconciliation"
  definition         = local.asl_definition
  role_arn           = module.iam.sfn_execution_role_arn
  log_retention_days = var.log_retention_days
  logging_level      = "ALL" # dev は原因調査のため全実行イベントを CloudWatch Logs に残す
  tags               = local.common_tags
}
