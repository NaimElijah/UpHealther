# The same Node release as frontend/.nvmrc, which CI reads (ADR-030, ADR-031).
FROM node:24.21.0-alpine3.24 AS build
WORKDIR /app
# .npmrc before npm ci, so engine-strict applies in the image too.
COPY package*.json .npmrc ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.30.5-alpine3.24
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80

# nginx serves a static bundle, so "is it answering" is the whole of its health.
# 127.0.0.1, not localhost: inside the container localhost resolves to ::1 first, and nginx.conf
# listens on IPv4 only, so a probe of localhost is refused and can never pass (#75).
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3     CMD wget -qO- http://127.0.0.1/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
