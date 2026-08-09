# ============================================================================
# prod 環境の実値。account_id と github_repository は必ず実際の値に書き換えること。
# 削除保護等の安全装置はここではなく main.tf に直接ハードコードしてあるため、
# このファイルを書き換えても緩められない。
# ============================================================================

account_id = "123456789012" # プレースホルダ。実際の prod アカウント ID に置き換える

aws_region         = "ap-northeast-1"
availability_zones = ["ap-northeast-1a", "ap-northeast-1c"]

vpc_cidr_block       = "10.30.0.0/16"
public_subnet_cidrs  = ["10.30.0.0/24", "10.30.1.0/24"]
private_subnet_cidrs = ["10.30.10.0/24", "10.30.11.0/24"]

rds_instance_class               = "db.r6g.large"
rds_allocated_storage_gb         = 100
rds_max_allocated_storage_gb     = 500
rds_backup_retention_period_days = 30

ecs_api_cpu                    = 1024
ecs_api_memory                 = 2048
ecs_api_desired_count          = 3
ecs_api_min_capacity           = 3
ecs_api_max_capacity           = 10
ecs_api_cpu_target_utilization = 60

ecs_batch_cpu    = 1024
ecs_batch_memory = 2048

log_retention_days = 365

settlement_bucket_name      = "template-settlements-prod" # 要: グローバルに一意な名前へ変更
settlement_queue_name       = "template-settlement-tasks-prod"
settlement_dispatch_pattern = "ecs_run_task"

github_repository        = "your-org/springboot-template" # 要: 実際の "org/repo" に置き換える
github_oidc_allowed_refs = ["refs/heads/main"]

terraform_state_bucket_name = "template-terraform-state-EXAMPLE_ACCOUNT_ID"
terraform_lock_table_name   = "template-terraform-locks"

# prodはHTTPS + Route 53を必須にしている。全て実値へ置き換えること。
api_certificate_arn = "arn:aws:acm:ap-northeast-1:123456789012:certificate/REPLACE_ME"
api_hosted_zone_id  = "Z_REPLACE_ME"
api_domain_name     = "api.example.com"
# alarm_email       = "platform@example.com"
