# Untested -- no Docker available in the environment this was written in.
# Build/run once you have Docker + an E2B API key:
#   docker build -t agent-hub-sandbox-sidecar .
#   docker run -p 8090:8090 -e E2B_API_KEY=... agent-hub-sandbox-sidecar
FROM node:20-slim
WORKDIR /app
COPY package.json .
RUN npm install --omit=dev
COPY server.js .
EXPOSE 8090
CMD ["node", "server.js"]
