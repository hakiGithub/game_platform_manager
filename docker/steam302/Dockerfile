# Steamcommunity 302 无头精简镜像
# 二进制为静态链接的 Go 程序，alpine 即可运行；容器必须 --network host
# 并由平台在宿主机侧管理 /etc/hosts（容器内 rename 必然 EBUSY，见 ADR-0019）。
# 可执行位在构建上下文中预置（build.sh），避免 RUN chmod 使二进制重复占一层。
FROM alpine:3.19

COPY steamcommunity_302.cli steamcommunity_302.caddy /opt/s302/
COPY S302.ini S302_rules.ini S302.hosts /opt/s302/
COPY entrypoint.sh /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
