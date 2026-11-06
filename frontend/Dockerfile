FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 화면은 VWorld 를 쓸지만 알면 된다 — 진짜 키는 nginx 환경변수로만 들어간다. Vite 는 이 값을 코드에 치환하므로
# 진짜 키를 넘기면 공개되는 JS 묶음에 박힌다
ARG USE_VWORLD
ENV VITE_VWORLD_KEY=$USE_VWORLD
RUN npm run build

FROM nginx:1.27-alpine
# templates 안의 파일은 켜질 때 환경변수를 채워 conf.d 로 들어간다
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
