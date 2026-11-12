FROM node:24-bookworm-slim AS node-runtime

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY --from=node-runtime /usr/local/bin/ /usr/local/bin/
COPY --from=node-runtime /usr/local/lib/node_modules/ /usr/local/lib/node_modules/
RUN corepack enable pnpm && corepack prepare pnpm@10.33.0 --activate
COPY platform/lumira-next/ .
COPY plugins/finance/ plugins/finance/
RUN pnpm install --frozen-lockfile
RUN ln -s /workspace/apps/web/node_modules /workspace/plugins/node_modules \
    && ln -s /workspace/apps/web/node_modules /workspace/plugins/sdk/frontend/node_modules \
    && for dir in /workspace/plugins/*/frontend; do \
         if [ -d "$dir" ] && [ ! -e "$dir/node_modules" ]; then \
           ln -s /workspace/apps/web/node_modules "$dir/node_modules"; \
         fi; \
       done
RUN apt-get update \
    && apt-get install --yes --no-install-recommends unzip zip \
    && rm -rf /var/lib/apt/lists/*
RUN ./backend/mvnw -f backend/pom.xml -B -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/plugin-classpath.txt
RUN pnpm plugins:build --no-host
RUN node scripts/plugin-runtime-lock.mjs --bundle backend/target/plugin-runtime/bundle \
    && grep -q '"finance"' backend/target/plugin-runtime/bundle/approved-graph.json

FROM eclipse-temurin:25-jre
WORKDIR /app
# The Ubuntu 26.04 base includes Canonical Pebble, which this JVM service does not use.
RUN rm -f /usr/bin/pebble
RUN apt-get update \
    && apt-get install --yes --no-install-recommends fontconfig fonts-wqy-zenhei fonts-noto-cjk \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/backend/target/platform-server-0.1.0-SNAPSHOT.jar app.jar
# Plugin bundles are part of the immutable release image; only extracted runtime state is writable.
COPY --from=build /workspace/backend/target/plugin-runtime/bundle /app/plugins
# The legacy local blob store is retained only for migration. Plugin extraction uses a private,
# container-local directory and never shares a writable plugin package volume between nodes.
RUN mkdir -p /data/files /tmp/lumira/plugins \
    && chown -R 10001:10001 /data/files /tmp/lumira/plugins \
    && chmod -R a-w /app/plugins
USER 10001:10001
ENV LUMIRA_BIND=0.0.0.0 \
    LUMIRA_FILES_LOCAL_ROOT=/data/files \
    LUMIRA_PLUGINS_PATH=/tmp/lumira/plugins \
    LUMIRA_PLUGINS_APPROVED_GRAPH=/app/plugins/approved-graph.json \
    LUMIRA_PLUGINS_FIXED_GRAPH=true
EXPOSE 8090
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
