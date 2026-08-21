# Cloudflare infrastructure

This template creates a proxied DNS record, a self-hosted Access application, a dedicated service token, and a Service Auth policy. It targets an existing Cloudflare Tunnel and never reads, stores, or rotates the tunnel token.

Copy `terraform.tfvars.example` to `terraform.tfvars`, set `CLOUDFLARE_API_TOKEN` in the environment, and use a secure remote backend or another protected state store. Terraform state contains the Access Client Secret and must be treated as sensitive.

```shell
terraform init
terraform fmt -check
terraform validate
terraform plan -out relay.tfplan
terraform apply relay.tfplan
```

Copy `cloudflared-config.yml.example` outside the repository and replace every placeholder. The default relay installation exposes a Unix socket; no router port forwarding or inbound firewall rule is needed.

Read the Client Secret without placing it in shell history:

```shell
terraform output -raw access_client_secret
```

Never commit `.tfvars`, state, plans, credentials JSON, service-token values, account IDs, zone IDs, tunnel IDs, or live hostnames.
