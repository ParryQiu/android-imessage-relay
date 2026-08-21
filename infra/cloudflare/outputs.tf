output "access_client_id" {
  description = "Enter this value on the Android setup screen."
  value       = cloudflare_zero_trust_access_service_token.android.client_id
}

output "access_client_secret" {
  description = "Sensitive one-time value. Transfer it directly to Android and do not save it in shell history."
  value       = cloudflare_zero_trust_access_service_token.android.client_secret
  sensitive   = true
}

output "relay_endpoint" {
  description = "Enter this HTTPS endpoint on Android."
  value       = "https://${var.hostname}"
}
