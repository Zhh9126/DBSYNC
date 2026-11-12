# DBDataSync 应用层镜像：基于本地基座，仅 COPY 应用，全程零联网。
# 升级时只需重建本层（FROM dbdatasync-base:8），基座层复用。
FROM dbdatasync-base:8

ARG DBS_VERSION=2.1.4
ENV DBDataSync_VERSION=${DBS_VERSION} \
    DBS_HOME=/app/DBDataSync \
    DBS_HEAP_MIN=256m \
    DBS_HEAP_MAX=1024m \
    DBS_DIRECT_MEM=256m

LABEL org.opencontainers.image.title="DBDataSync" \
      org.opencontainers.image.version="${DBS_VERSION}" \
      org.opencontainers.image.description="Offline DBDataSync with SQLite metastore, JDBC drivers and built-in manual"

WORKDIR /app/DBDataSync

COPY app/ /app/DBDataSync/
COPY default-conf/ /app/default-conf/

RUN chmod +x /app/DBDataSync/bin/*.sh /app/DBDataSync/tools/*.sh \
 && mkdir -p /app/DBDataSync/conf /app/DBDataSync/data /app/DBDataSync/logs /app/DBDataSync/plugins

EXPOSE 18686
VOLUME ["/app/DBDataSync/conf", "/app/DBDataSync/data", "/app/DBDataSync/logs", "/app/DBDataSync/plugins"]

ENTRYPOINT ["/bin/bash", "/app/DBDataSync/bin/startup-docker.sh"]
