# Build from the frontend/ directory:
#   docker build -t agent-hub-frontend --build-arg API_BASE_URL=http://localhost:8080 .
#
# API_BASE_URL is baked into the JS bundle at build time -- Angular has no
# runtime config mechanism here, so it must be the URL the BROWSER (not this
# container) can reach gateway-api at. For docker-compose on one machine
# that's the host-published gateway-api port (e.g. http://localhost:8080),
# not the in-network service name -- the browser runs outside the compose
# network entirely.
FROM node:22-slim AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG API_BASE_URL=http://localhost:8080
# environment.prod.ts's placeholder is swapped for the real deploy target --
# see fileReplacements in angular.json for where this file gets picked up.
RUN sed -i "s#https://api.yourplatform.com#${API_BASE_URL}#" src/environments/environment.prod.ts
RUN npm run build -- --configuration production

FROM nginx:1.27-alpine
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
