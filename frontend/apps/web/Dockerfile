# Runtime image for the Ivorovi web app. Packages the output of `pnpm --filter @ivorovi/web build`.
# One image for every deployment: /config.json is generated from environment variables at start-up.
FROM nginx:1.30.6-alpine3.24
# Pick up OS security fixes published after the base image was built.
RUN apk upgrade --no-cache
COPY apps/web/deploy/default.conf.template /etc/nginx/templates/default.conf.template
COPY apps/web/deploy/40-ivorovi-config.sh /docker-entrypoint.d/40-ivorovi-config.sh
COPY apps/web/dist /usr/share/nginx/html
RUN chmod +x /docker-entrypoint.d/40-ivorovi-config.sh && rm -f /etc/nginx/conf.d/default.conf
ENV API_UPSTREAM=http://api:8080 OIDC_CLIENT_ID=ivorovi-web
EXPOSE 8080
