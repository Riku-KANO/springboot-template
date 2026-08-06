# ============================================================================
# stg 環境の実値。account_id と github_repository は必ず実際の値に書き換えること。
# ============================================================================

account_id = "123456789012" # プレースホルダ。実際の stg アカウント ID に置き換える

aws_region         = "ap-northeast-1"
availability_zones = ["ap-northeast-1a", "ap-northeast-1c"]

vpc_cidr_block       = "10.20.0.0/16"
public_subnet_cidrs  = ["10.20.0.0/24", "10.20.1.0/24"]
private_subnet_cidrs = ["10.20.10.0/24", "10.20.11.0/24"]
single_nat_gateway   = false

rds_instance_class               = "db.t4g.small"
rds_allocated_storage_gb         = 50
rds_max_allocated_storage_gb     = 200
rds_backup_retention_period_days = 7

ecs_api_cpu           = 512
ecs_api_memory        = 1024
ecs_api_desired_count = 2
ecs_api_min_capacity  = 2
ecs_api_max_capacity  = 4

ecs_batch_cpu    = 512
ecs_batch_memory = 1024

log_retention_days = 30

settlement_bucket_name      = "template-settlements-stg" # 要: グローバルに一意な名前へ変更
settlement_queue_name       = "template-settlement-tasks-stg"
settlement_dispatch_pattern = "ecs_run_task"

github_repository        = "your-org/springboot-template" # 要: 実際の "org/repo" に置き換える
github_oidc_allowed_refs = ["refs/heads/main", "refs/tags/*"]
