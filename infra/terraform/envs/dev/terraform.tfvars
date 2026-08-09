# ============================================================================
# dev 環境の実値。account_id と github_repository は必ず実際の値に書き換えること。
# ============================================================================

account_id = "123456789012" # プレースホルダ。実際の dev アカウント ID に置き換える

aws_region         = "ap-northeast-1"
availability_zones = ["ap-northeast-1a", "ap-northeast-1c"]

vpc_cidr_block       = "10.10.0.0/16"
public_subnet_cidrs  = ["10.10.0.0/24", "10.10.1.0/24"]
private_subnet_cidrs = ["10.10.10.0/24", "10.10.11.0/24"]
single_nat_gateway   = true

rds_instance_class               = "db.t4g.micro"
rds_allocated_storage_gb         = 20
rds_max_allocated_storage_gb     = 50
rds_multi_az                     = false
rds_deletion_protection          = false
rds_skip_final_snapshot          = true
rds_backup_retention_period_days = 1

ecs_api_cpu           = 256
ecs_api_memory        = 512
ecs_api_desired_count = 1
ecs_api_min_capacity  = 1
ecs_api_max_capacity  = 1

ecs_batch_cpu    = 512
ecs_batch_memory = 1024

fargate_spot_enabled = true
log_retention_days   = 7

settlement_bucket_name          = "template-settlements-dev" # 要: グローバルに一意な名前へ変更
settlement_bucket_force_destroy = true
settlement_queue_name           = "template-settlement-tasks-dev"
settlement_dispatch_pattern     = "ecs_run_task"

github_repository           = "your-org/springboot-template" # 要: 実際の "org/repo" に置き換える
github_oidc_allowed_refs    = ["refs/heads/main"]
create_github_oidc_provider = true # OIDC プロバイダは dev だけが作る (stg/prod は false のまま)

terraform_state_bucket_name = "template-terraform-state-EXAMPLE_ACCOUNT_ID"
terraform_lock_table_name   = "template-terraform-locks"

# 任意: ACM + Route 53を設定するとHTTPS + 独自ドメインになる。未設定ならALBのHTTP endpointを使う。
# api_certificate_arn = "arn:aws:acm:ap-northeast-1:123456789012:certificate/REPLACE_ME"
# api_hosted_zone_id  = "Z_REPLACE_ME"
# api_domain_name     = "api-dev.example.com"
# alarm_email         = "platform@example.com"
