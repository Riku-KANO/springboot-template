output "target_group_arn" {
  value = aws_lb_target_group.api.arn
}

output "dns_name" {
  value = aws_lb.this.dns_name
}

output "arn_suffix" {
  value = aws_lb.this.arn_suffix
}

output "target_group_arn_suffix" {
  value = aws_lb_target_group.api.arn_suffix
}

output "endpoint" {
  value = var.domain_name != null ? "${var.certificate_arn != null ? "https" : "http"}://${var.domain_name}" : "${var.certificate_arn != null ? "https" : "http"}://${aws_lb.this.dns_name}"
}
