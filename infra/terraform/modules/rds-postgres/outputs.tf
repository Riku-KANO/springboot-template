output "endpoint" {
  description = "接続エンドポイント (ホスト:ポート)。"
  value       = aws_db_instance.this.endpoint
}

output "address" {
  description = "ホスト名のみ (ポートを含まない)。"
  value       = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "db_name" {
  value = aws_db_instance.this.db_name
}

output "instance_id" {
  value = aws_db_instance.this.id
}
