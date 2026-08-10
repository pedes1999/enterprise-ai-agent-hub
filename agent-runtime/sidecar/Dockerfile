# Verified: built and run against a real E2B account
# (RunShellCommandToolManualIT passed through this exact container).
#   docker build -t agent-hub-sandbox-sidecar .
#   docker run -p 8090:8090 --env-file .env agent-hub-sandbox-sidecar
FROM node:20-slim
WORKDIR /app
COPY package.json .
RUN npm install --omit=dev
COPY server.js .
EXPOSE 8090
CMD ["node", "server.js"]
