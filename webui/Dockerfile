# syntax=docker/dockerfile:1.7
# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM node:20-alpine AS builder

WORKDIR /app

# Install deps first (cached layer unless package.json changes)
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --prefer-offline

COPY . .
RUN npm run build

# ─── Stage 2: Runtime (nginx) ─────────────────────────────────────────────────
FROM nginx:stable-alpine

# Run nginx as non-root user (nginx user already exists in the image)
RUN sed -i 's/listen\s*80/listen 8080/g' /etc/nginx/conf.d/default.conf 2>/dev/null; \
    mkdir -p /var/cache/nginx /var/run && \
    chown -R nginx:nginx /var/cache/nginx /var/run /usr/share/nginx/html /etc/nginx/conf.d

# Copy built assets and config
COPY --from=builder --chown=nginx:nginx /app/dist /usr/share/nginx/html
COPY --chown=nginx:nginx nginx.conf /etc/nginx/conf.d/default.conf

USER nginx

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://localhost/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
