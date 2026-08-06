# ============================================================================
# rds-postgres モジュール
#
# :bootstrap が JDBC (Flyway + Spring Batch 6 の JobRepository) と R2DBC (アプリの実行時クエリ) の
# 両方から同じインスタンスを指す "デュアル DataSource/ConnectionFactory" 構成 (詳細は
# bootstrap/.../config/JdbcDataSourceConfig.kt および docs/adr を参照) を前提にしているため、
# ここで作るのは単一の PostgreSQL インスタンスであり、JDBC 用/R2DBC 用に分ける必要はない。
#
# マスターパスワードはこのモジュールが生成するのではなく、呼び出し側 (envs/*/main.tf) が
# 生成して渡す (master_password, sensitive 変数)。あえて RDS 標準の
# "manage_master_user_password" (AWS 管理の Secrets Manager シークレット) を使わなかったのは、
# application-{dev,stg,prod}.yml の spring.config.import が
# "aws-secretsmanager:template/{env}/db-credentials" という固定名のシークレットを直接読みに行く
# 設計になっているため。RDS 管理シークレットは名前を選べず (rds!db-... 形式で自動採番される)、
# アプリ側が期待する名前と一致しないため二重管理・橋渡しが必要になってしまう。envs/*/main.tf が
# 生成したパスワードを「template/{env}/db-credentials という名前のシークレット」と
# 「この DB インスタンスのマスターパスワード」の両方に同時に使うことで、二重管理を避けている。
# ============================================================================

resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-subnet-group"
  subnet_ids = var.subnet_ids

  tags = var.tags
}

resource "aws_db_instance" "this" {
  identifier     = var.name
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  db_name  = var.db_name
  username = var.master_username
  password = var.master_password

  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.max_allocated_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = var.allowed_security_group_ids
  publicly_accessible    = false

  multi_az                     = var.multi_az
  backup_retention_period      = var.backup_retention_period_days
  deletion_protection          = var.deletion_protection
  skip_final_snapshot          = var.skip_final_snapshot
  final_snapshot_identifier    = var.skip_final_snapshot ? null : "${var.name}-final-${formatdate("YYYYMMDDhhmmss", timestamp())}"
  performance_insights_enabled = var.performance_insights_enabled
  apply_immediately            = var.apply_immediately

  # V0__batch_schema.sql (Spring Batch 6 のメタデータ) を含め、全スキーマは Flyway が
  # 唯一の情報源として適用する (dev は :bootstrap 起動時、stg/prod はデプロイパイプラインの
  # 専用ステップで `flyway migrate` を実行する。application-{stg,prod}.yml の
  # spring.flyway.enabled=false を参照)。そのため RDS 側でパラメータグループによる
  # 追加のスキーマ初期化は行わない。

  tags = var.tags
}
