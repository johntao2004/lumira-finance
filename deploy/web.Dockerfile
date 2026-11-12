FROM node:24-bookworm-slim AS build
WORKDIR /workspace
RUN corepack enable pnpm && corepack prepare pnpm@10.33.0 --activate
COPY platform/lumira-next/package.json platform/lumira-next/pnpm-workspace.yaml platform/lumira-next/pnpm-lock.yaml ./
COPY platform/lumira-next/apps/web/package.json apps/web/package.json
COPY platform/lumira-next/patches/ patches/
RUN pnpm install --frozen-lockfile
COPY platform/lumira-next/apps/web apps/web
COPY platform/lumira-next/plugins plugins
COPY plugins/finance plugins/finance
RUN ln -s /workspace/apps/web/node_modules /workspace/plugins/node_modules \
    && ln -s /workspace/apps/web/node_modules /workspace/plugins/sdk/frontend/node_modules \
    && for dir in /workspace/plugins/*/frontend; do \
         if [ -d "$dir" ] && [ ! -e "$dir/node_modules" ]; then \
           ln -s /workspace/apps/web/node_modules "$dir/node_modules"; \
         fi; \
       done
RUN pnpm build
COPY platform/lumira-next/scripts/generate-csp.mjs scripts/generate-csp.mjs
RUN node scripts/generate-csp.mjs apps/web/dist/index.html /workspace/csp.caddy

FROM golang:1.26.9-alpine3.23 AS caddy-build
RUN apk add --no-cache git
RUN go install github.com/caddyserver/xcaddy/cmd/xcaddy@v0.4.5
RUN CGO_ENABLED=0 XCADDY_SKIP_CLEANUP=1 XCADDY_SETCAP=0 /go/bin/xcaddy build v2.11.7 --output /out/caddy \
    && go version -m /out/caddy | grep -F 'go1.26.9'

FROM caddy:2.11.7-alpine
COPY --from=caddy-build /out/caddy /usr/bin/caddy
COPY deploy/web.Caddyfile /etc/caddy/Caddyfile
COPY --from=build /workspace/csp.caddy /etc/caddy/csp.caddy
COPY --from=build /workspace/apps/web/dist /srv
EXPOSE 8010
