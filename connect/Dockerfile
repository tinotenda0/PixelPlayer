FROM node:22-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev && npm cache clean --force

COPY server.js ./
COPY web ./web

# Broker identity + caches persist here; mount a volume to keep it stable.
VOLUME ["/app/data"]

EXPOSE 32599

CMD ["node", "server.js"]
