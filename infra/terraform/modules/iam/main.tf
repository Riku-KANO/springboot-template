# ============================================================================
# iam モジュール
#
# 以下のロールをまとめて作る。
#   1. ecs_task_execution_role : ECS エージェントが ECR pull / CloudWatch Logs 書き込み /
#      (container definition の "secrets" 経由の) Secrets Manager 読み出しを行うためのロール。
#      長時間稼働の API サービスとワンショットのバッチタスクの両方の task definition が共有する。
#   2. ecs_api_task_role : 長時間稼働の API コンテナ自身 (アプリケーションコード) が引き受けるロール。
#      spring.config.import (SSM/Secrets Manager) の実行時読み出し、SettlementTaskListener
#      (@SqsListener は batch プロファイルでのみ無効化されるため、API 側でも常駐している) の
#      SQS 受信、SfnTaskCallbackAdapter の SendTaskSuccess/SendTaskFailure (ジョブ起動失敗時の
#      通知経路) をカバーする。
#   3. ecs_batch_task_role : ワンショットの消込バッチタスクが引き受けるロール。S3 の消込ファイル読み込み、
#      SSM/Secrets Manager の実行時読み出し、そして最重要の states:SendTaskSuccess/SendTaskFailure
#      (SettlementJobListener が Step Functions の .waitForTaskToken を閉じるために必須)。
#   4. sfn_execution_role : ステートマシン自身が ecs:RunTask (Pattern B) や sqs:SendMessage
#      (Pattern A) を呼ぶためのロール。
#   5. github_actions_oidc_role : CI (.github/workflows/docker-build-push.yml) が長期輌の
#      アクセスキーを持たずに ECR へ push するための OIDC フェデレーションロール。
# ============================================================================

data "aws_iam_policy_document" "ecs_tasks_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# --- 1. ECS タスク実行ロール (ECS エージェント自身が使う) ---

resource "aws_iam_role" "ecs_task_execution" {
  name               = "${var.name}-ecs-task-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution_managed" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "ecs_task_execution_secrets" {
  statement {
    sid       = "ReadDbSecretForContainerEnvInjection"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.db_secret_arn]
  }
}

resource "aws_iam_role_policy" "ecs_task_execution_secrets" {
  name   = "read-db-secret"
  role   = aws_iam_role.ecs_task_execution.id
  policy = data.aws_iam_policy_document.ecs_task_execution_secrets.json
}

# --- 共通: SSM/Secrets Manager のランタイム読み出し (spring.config.import) ---

data "aws_iam_policy_document" "runtime_config_read" {
  statement {
    sid       = "ReadSsmParameters"
    effect    = "Allow"
    actions   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
    resources = [var.ssm_parameter_path_arn]
  }

  statement {
    sid       = "ReadDbSecret"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.db_secret_arn]
  }
}

# --- 2. 長時間稼働 API タスクロール ---

resource "aws_iam_role" "ecs_api_task" {
  name               = "${var.name}-ecs-api-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
  tags               = var.tags
}

resource "aws_iam_role_policy" "ecs_api_task_runtime_config" {
  name   = "runtime-config-read"
  role   = aws_iam_role.ecs_api_task.id
  policy = data.aws_iam_policy_document.runtime_config_read.json
}

data "aws_iam_policy_document" "ecs_api_task_extra" {
  statement {
    sid       = "ReceiveSettlementTaskMessages"
    effect    = "Allow"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility"]
    resources = [var.settlement_queue_arn]
  }

  statement {
    # SettlementTaskListener.reportLaunchFailure (ジョブ「起動」自体の失敗) は API プロセス側でも
    # 発生しうるため、API タスクロールにも SendTaskFailure/SendTaskSuccess を持たせる。
    sid       = "NotifyStepFunctionsOnLaunchFailure"
    effect    = "Allow"
    actions   = ["states:SendTaskSuccess", "states:SendTaskFailure", "states:SendTaskHeartbeat"]
    resources = ["*"] # タスクトークンは実行のたびに動的に発行されるため ARN で絞り込めない (AWS 側の制約)
  }

  statement {
    sid       = "ReadSettlementFiles"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:ListBucket"]
    resources = [var.settlement_bucket_arn, "${var.settlement_bucket_arn}/*"]
  }
}

resource "aws_iam_role_policy" "ecs_api_task_extra" {
  name   = "settlement-messaging"
  role   = aws_iam_role.ecs_api_task.id
  policy = data.aws_iam_policy_document.ecs_api_task_extra.json
}

# --- 3. ワンショット消込バッチタスクロール ---

resource "aws_iam_role" "ecs_batch_task" {
  name               = "${var.name}-ecs-batch-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
  tags               = var.tags
}

resource "aws_iam_role_policy" "ecs_batch_task_runtime_config" {
  name   = "runtime-config-read"
  role   = aws_iam_role.ecs_batch_task.id
  policy = data.aws_iam_policy_document.runtime_config_read.json
}

data "aws_iam_policy_document" "ecs_batch_task_extra" {
  statement {
    sid       = "ReadSettlementFiles"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:ListBucket"]
    resources = [var.settlement_bucket_arn, "${var.settlement_bucket_arn}/*"]
  }

  statement {
    # SettlementJobListener.afterJob が .waitForTaskToken を閉じるために必須。
    # これが無いと、バッチジョブは完走してもステートマシンが SUCCEEDED/FAILED に遷移せず
    # タイムアウトまで待ち続ける (docs/runbooks/settlement-batch.md 参照)。
    sid       = "NotifyStepFunctionsOfJobOutcome"
    effect    = "Allow"
    actions   = ["states:SendTaskSuccess", "states:SendTaskFailure", "states:SendTaskHeartbeat"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "ecs_batch_task_extra" {
  name   = "settlement-job-callback"
  role   = aws_iam_role.ecs_batch_task.id
  policy = data.aws_iam_policy_document.ecs_batch_task_extra.json
}

# --- 4. Step Functions ステートマシンの実行ロール ---

data "aws_iam_policy_document" "sfn_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["states.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "sfn_execution" {
  name               = "${var.name}-sfn-execution"
  assume_role_policy = data.aws_iam_policy_document.sfn_assume_role.json
  tags               = var.tags
}

data "aws_iam_policy_document" "sfn_execution_policy" {
  # Pattern B: ecs:runTask.waitForTaskToken
  statement {
    sid       = "RunSettlementBatchTask"
    effect    = "Allow"
    actions   = ["ecs:RunTask"]
    resources = ["${var.ecs_task_definition_arn_pattern}:*"]
  }

  statement {
    sid       = "TrackRunningTask"
    effect    = "Allow"
    actions   = ["ecs:StopTask", "ecs:DescribeTasks"]
    resources = ["*"]
  }

  statement {
    sid     = "PassEcsRoles"
    effect  = "Allow"
    actions = ["iam:PassRole"]
    resources = [
      aws_iam_role.ecs_task_execution.arn,
      aws_iam_role.ecs_batch_task.arn,
    ]
  }

  # ECS RunTask (.sync/.waitForTaskToken いずれも) は異常終了検知のために Step Functions が
  # 内部で使う AWS 管理の EventBridge ルールへのアクセスを要求する。AWS 公式ドキュメント
  # "Manage ECS/Fargate Tasks with Step Functions" が明示する固定リソース名。
  statement {
    sid    = "ManageEcsTaskEventRule"
    effect = "Allow"
    actions = [
      "events:PutTargets",
      "events:PutRule",
      "events:DescribeRule",
    ]
    resources = ["arn:aws:events:${var.region}:${var.account_id}:rule/StepFunctionsGetEventForECSTaskRule"]
  }

  # Pattern A: sqs:sendMessage.waitForTaskToken (本番相当環境でも選択可能にしておく)
  statement {
    sid       = "SendSettlementTaskMessage"
    effect    = "Allow"
    actions   = ["sqs:SendMessage"]
    resources = [var.settlement_queue_arn]
  }

  # ステートマシンの通知ステート (Choice で分岐した先の SNS 通知) 用。
  statement {
    sid       = "PublishNotifications"
    effect    = "Allow"
    actions   = ["sns:Publish"]
    resources = ["arn:aws:sns:${var.region}:${var.account_id}:${var.name}-*"]
  }
}

resource "aws_iam_role_policy" "sfn_execution" {
  name   = "sfn-execution-policy"
  role   = aws_iam_role.sfn_execution.id
  policy = data.aws_iam_policy_document.sfn_execution_policy.json
}

# --- 5. GitHub Actions OIDC ---

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # GitHub の OIDC 発行者証明書の thumbprint。GitHub 側のドキュメントが公開している値
  # (プロバイダ作成時に AWS SDK/コンソールが検証する。将来証明書が更新された場合は
  # 都度差し替えが必要 — 詳細は docs/how-to-use-this-template.md の OIDC ブートストラップ手順を参照)。
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]

  tags = var.tags
}

locals {
  github_oidc_provider_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : var.existing_github_oidc_provider_arn
}

data "aws_iam_policy_document" "github_actions_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [for ref in var.github_oidc_allowed_refs : "repo:${var.github_repository}:ref:${ref}"]
    }
  }
}

resource "aws_iam_role" "github_actions" {
  name               = "${var.name}-github-actions-oidc"
  assume_role_policy = data.aws_iam_policy_document.github_actions_assume_role.json
  tags               = var.tags
}

data "aws_iam_policy_document" "github_actions_policy" {
  statement {
    sid       = "EcrAuth"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPush"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
    ]
    resources = [var.ecr_repository_arn]
  }
}

resource "aws_iam_role_policy" "github_actions" {
  name   = "ecr-push"
  role   = aws_iam_role.github_actions.id
  policy = data.aws_iam_policy_document.github_actions_policy.json
}
