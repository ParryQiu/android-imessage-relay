variable "account_id" {
  description = "Cloudflare account ID. Store the value outside version control."
  type        = string
  sensitive   = true
}

variable "zone_id" {
  description = "Cloudflare zone ID. Store the value outside version control."
  type        = string
  sensitive   = true
}

variable "tunnel_id" {
  description = "Existing Cloudflare Tunnel ID. This template never reads or rotates its token."
  type        = string
  sensitive   = true
}

variable "hostname" {
  description = "Public relay hostname protected by Cloudflare Access."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])$", var.hostname))
    error_message = "hostname must be a lower-case DNS hostname."
  }
}

variable "service_token_duration" {
  description = "Lifetime of the dedicated Android service token."
  type        = string
  default     = "8760h"
}
