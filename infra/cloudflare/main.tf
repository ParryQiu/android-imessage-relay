resource "cloudflare_dns_record" "relay" {
  zone_id = var.zone_id
  name    = var.hostname
  type    = "CNAME"
  content = "${var.tunnel_id}.cfargotunnel.com"
  ttl     = 1
  proxied = true
  comment = "Android iMessage Relay"
}

resource "cloudflare_zero_trust_access_service_token" "android" {
  account_id = var.account_id
  name       = "Android iMessage Relay"
  duration   = var.service_token_duration

  lifecycle {
    create_before_destroy = true
  }
}

resource "cloudflare_zero_trust_access_policy" "service_auth" {
  account_id = var.account_id
  name       = "Android iMessage Relay Service Auth"
  decision   = "non_identity"
  include = [{
    service_token = {
      token_id = cloudflare_zero_trust_access_service_token.android.id
    }
  }]
}

resource "cloudflare_zero_trust_access_application" "relay" {
  account_id           = var.account_id
  name                 = "Android iMessage Relay"
  domain               = var.hostname
  type                 = "self_hosted"
  app_launcher_visible = false
  session_duration     = "24h"
  policies = [{
    id         = cloudflare_zero_trust_access_policy.service_auth.id
    precedence = 1
  }]
}
