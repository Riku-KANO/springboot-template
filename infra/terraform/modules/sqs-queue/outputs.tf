output "queue_url" {
  value = aws_sqs_queue.this.url
}

output "dlq_name" {
  value = var.enable_dlq ? aws_sqs_queue.dlq[0].name : null
}

output "queue_arn" {
  value = aws_sqs_queue.this.arn
}

output "dlq_arn" {
  value = var.enable_dlq ? aws_sqs_queue.dlq[0].arn : null
}
