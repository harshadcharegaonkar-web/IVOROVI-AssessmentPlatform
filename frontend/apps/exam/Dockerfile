# Runtime image of the exam application (separate origin, C-4). Packages `pnpm --filter @ivorovi/exam build`.
FROM nginx:1.30.6-alpine3.24
RUN apk upgrade --no-cache
COPY apps/exam/deploy/default.conf.template /etc/nginx/templates/default.conf.template
COPY apps/exam/deploy/40-ivorovi-exam-config.sh /docker-entrypoint.d/40-ivorovi-exam-config.sh
COPY apps/exam/dist /usr/share/nginx/html
RUN chmod +x /docker-entrypoint.d/40-ivorovi-exam-config.sh && rm -f /etc/nginx/conf.d/default.conf
ENV DELIVERY_UPSTREAM=http://delivery:8080 OIDC_CLIENT_ID=ivorovi-exam
EXPOSE 8080
