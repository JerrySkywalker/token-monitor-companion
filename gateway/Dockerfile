FROM node:24-bookworm-slim
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY src/ src/
COPY fixtures/ fixtures/
RUN mkdir /data && chown node:node /data
USER node
ENV COMPANION_HOST=0.0.0.0 COMPANION_PORT=8787 COMPANION_DB=/data/companion.sqlite
EXPOSE 8787
CMD ["npm", "start"]
