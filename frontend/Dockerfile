FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80

# nginx serves a static bundle, so "is it answering" is the whole of its health.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3     CMD wget -qO- http://localhost/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
