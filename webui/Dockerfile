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

# Fix nginx config for non-root execution (user must be commented out, pid moved to /tmp)
RUN sed -i 's/^user/#user/' /etc/nginx/nginx.conf && \
    sed -i 's|^pid.*|pid /tmp/nginx.pid;|' /etc/nginx/nginx.conf

# Ensure dirs are writable by nginx user
RUN mkdir -p /var/cache/nginx /var/run && \
    chown -R nginx:nginx /var/cache/nginx /var/run /usr/share/nginx/html /etc/nginx/conf.d

# Copy built assets and config
COPY --from=builder --chown=nginx:nginx /app/dist /usr/share/nginx/html
COPY --chown=nginx:nginx nginx.conf /etc/nginx/conf.d/default.conf

USER nginx

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
