FROM node:22-alpine
WORKDIR /app
COPY api/package.json ./api/
RUN cd api && npm install --omit=dev
COPY api ./api
COPY web ./web
WORKDIR /app/api
EXPOSE 4000
CMD ["npm", "start"]
