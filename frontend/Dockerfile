FROM node:22-alpine AS build
RUN corepack enable && corepack prepare pnpm@11.8.0 --activate
WORKDIR /workspace
# Prefer npmmirror in China to avoid registry.npmjs.org timeouts during image builds.
RUN npm config set registry https://registry.npmmirror.com \
  && pnpm config set registry https://registry.npmmirror.com

ARG APP
ARG DIST_SUBPATH=
# Empty = same-origin /api via nginx proxy (docker). Local vite still defaults in source.
ARG VITE_API_BASE_URL=
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

COPY package.json tsconfig.base.json ./
COPY packages ./packages
# Only the target app — avoids vite version clashes from other apps (e.g. uni-app pins 5.2.8).
COPY apps/${APP} ./apps/${APP}
RUN printf '%s\n' \
  'packages:' \
  "  - 'apps/${APP}'" \
  "  - 'packages/*'" \
  'onlyBuiltDependencies:' \
  "  - '@parcel/watcher'" \
  '  - esbuild' \
  '  - vue-demi' \
  '  - core-js' \
  'allowBuilds:' \
  "  '@parcel/watcher': true" \
  '  esbuild: true' \
  '  vue-demi: true' \
  '  core-js: true' \
  > pnpm-workspace.yaml
# Drop root vite override noise for single-app image builds if present; install from lock-free resolve.
RUN pnpm install --frozen-lockfile=false
RUN pnpm --filter "@geek-mall/${APP}" build && cp -r apps/${APP}/dist${DIST_SUBPATH} /dist

FROM nginx:1.27-alpine
COPY deploy/nginx-spa.conf /etc/nginx/conf.d/default.conf
COPY --from=build /dist /usr/share/nginx/html
HEALTHCHECK --interval=15s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1/healthz || exit 1
