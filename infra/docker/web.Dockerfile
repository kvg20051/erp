FROM node:24-bookworm-slim AS build
WORKDIR /src
COPY apps/web/package*.json ./
RUN npm ci --no-audit --no-fund
COPY apps/web/ .
RUN npm run build
FROM nginx:stable-alpine
COPY --from=build /src/dist /usr/share/nginx/html
