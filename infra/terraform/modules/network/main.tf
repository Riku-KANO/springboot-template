# ============================================================================
# network モジュール
#
# VPC / public・private サブネット / IGW / NAT Gateway / ルーティング / セキュリティグループ を
# まとめて作る。ECS サービス (長時間稼働の API) と ECS の単発バッチタスクは private サブネットに、
# RDS (PostgreSQL) も private サブネットに配置する想定 (publicly_accessible = false)。
#
# セキュリティグループはここで「誰から誰への通信を許すか」という関係だけを定義し、
# ポート番号等の詳細な穴あけは呼び出し側 (envs/*) が rds-postgres / ecs-service モジュールに
# security_group_ids を渡す形で組み立てる。
# ============================================================================

locals {
  az_count = length(var.availability_zones)
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr_block
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(var.tags, {
    Name = "${var.name}-vpc"
  })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${var.name}-igw"
  })
}

resource "aws_subnet" "public" {
  count                   = local.az_count
  vpc_id                  = aws_vpc.this.id
  cidr_block              = var.public_subnet_cidrs[count.index]
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = true

  tags = merge(var.tags, {
    Name = "${var.name}-public-${var.availability_zones[count.index]}"
    Tier = "public"
  })
}

resource "aws_subnet" "private" {
  count             = local.az_count
  vpc_id            = aws_vpc.this.id
  cidr_block        = var.private_subnet_cidrs[count.index]
  availability_zone = var.availability_zones[count.index]

  tags = merge(var.tags, {
    Name = "${var.name}-private-${var.availability_zones[count.index]}"
    Tier = "private"
  })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = merge(var.tags, {
    Name = "${var.name}-public-rt"
  })
}

resource "aws_route_table_association" "public" {
  count          = local.az_count
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# NAT Gateway 用の Elastic IP。single_nat_gateway なら1個、そうでなければ AZ の数だけ。
resource "aws_eip" "nat" {
  count  = var.enable_nat_gateway ? (var.single_nat_gateway ? 1 : local.az_count) : 0
  domain = "vpc"

  tags = merge(var.tags, {
    Name = "${var.name}-nat-eip-${count.index}"
  })
}

resource "aws_nat_gateway" "this" {
  count         = var.enable_nat_gateway ? (var.single_nat_gateway ? 1 : local.az_count) : 0
  allocation_id = aws_eip.nat[count.index].id
  # single_nat_gateway = true のときは常に public サブネットの先頭 (index 0) に置く。
  subnet_id = aws_subnet.public[var.single_nat_gateway ? 0 : count.index].id

  tags = merge(var.tags, {
    Name = "${var.name}-nat-${count.index}"
  })

  depends_on = [aws_internet_gateway.this]
}

# private サブネットごとのルートテーブル。NAT Gateway が無効な場合はインターネットへの
# デフォルトルートを持たない (VPC エンドポイント経由でのみ AWS サービスに到達する構成を想定)。
resource "aws_route_table" "private" {
  count  = local.az_count
  vpc_id = aws_vpc.this.id

  dynamic "route" {
    for_each = var.enable_nat_gateway ? [1] : []
    content {
      cidr_block     = "0.0.0.0/0"
      nat_gateway_id = var.single_nat_gateway ? aws_nat_gateway.this[0].id : aws_nat_gateway.this[count.index].id
    }
  }

  tags = merge(var.tags, {
    Name = "${var.name}-private-rt-${var.availability_zones[count.index]}"
  })
}

resource "aws_route_table_association" "private" {
  count          = local.az_count
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

# --- セキュリティグループ ---
# ALB -> ECS サービス(API) -> RDS という一方向の許可チェーンを組む。
# ECS のワンショットバッチタスクも ECS サービスと同じ SG を共有し、RDS への到達性を得る。

resource "aws_security_group" "alb" {
  # AWS の SecurityGroupRule description は ASCII の一部記号しか許可しないため、
  # description 属性自体は英語で簡潔に書き、背景説明はコメントとして残す。
  # ALB 用。インターネットからの HTTP/HTTPS のみを許可する。
  name_prefix = "${var.name}-alb-"
  description = "ALB: allow inbound HTTP/HTTPS from the internet"
  vpc_id      = aws_vpc.this.id

  ingress {
    description = "HTTPS from internet"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # ACM 証明書未設定の検証環境向けに 80 番も開けておく。
  # stg/prod では ALB リスナー側で 443 へリダイレクトする運用を想定。
  ingress {
    description = "HTTP from internet (redirected to HTTPS at the ALB listener in stg/prod)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.name}-alb-sg" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "ecs_service" {
  # 長時間稼働の API (ECS Fargate service) 用。ALB からのみコンテナポートへの通信を許可する。
  name_prefix = "${var.name}-ecs-service-"
  description = "ECS API service: allow inbound from ALB only"
  vpc_id      = aws_vpc.this.id

  ingress {
    description     = "from ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.name}-ecs-service-sg" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "ecs_task" {
  # settlementReconciliationJob を実行するワンショット ECS タスク (batch プロファイル) 用。
  # インバウンドは受けない (Step Functions からの RunTask 起動のみ)。
  name_prefix = "${var.name}-ecs-task-"
  description = "ECS one-shot settlement batch task: no inbound, egress only"
  vpc_id      = aws_vpc.this.id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.name}-ecs-task-sg" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "rds" {
  # RDS (PostgreSQL) 用。ECS サービス/ECS バッチタスクの SG からのみ 5432 を許可する。
  name_prefix = "${var.name}-rds-"
  description = "RDS PostgreSQL: allow inbound 5432 from ECS service/task security groups only"
  vpc_id      = aws_vpc.this.id

  # R2DBC (アプリの実行時クエリ経路) からの到達性。
  ingress {
    description     = "from ECS service (API, R2DBC)"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_service.id]
  }

  # JDBC (Flyway のマイグレーション、Spring Batch 6 の JobRepository、settlements/errors テーブルへの書き込み) からの到達性。
  ingress {
    description     = "from ECS batch task (JDBC: Flyway/Batch metadata, settlement results/errors)"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_task.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.name}-rds-sg" })

  lifecycle {
    create_before_destroy = true
  }
}
