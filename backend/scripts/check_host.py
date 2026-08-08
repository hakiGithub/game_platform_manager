#!/usr/bin/env python
"""SSH connect to host and inspect docker containers / processes."""
import sys
import paramiko

HOST = "192.168.111.253"
PORT = 22
USER = "haki"
PASSWORD = "123456"


def run(client, cmd):
    print(f"\n>>> {cmd}")
    stdin, stdout, stderr = client.exec_command(cmd, timeout=30)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    rc = stdout.channel.recv_exit_status()
    print(f"[exit={rc}]")
    if out:
        print(out)
    if err:
        print("STDERR:", err)


def main():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print(f"Connecting to {USER}@{HOST}:{PORT} ...")
    client.connect(HOST, port=PORT, username=USER, password=PASSWORD, timeout=15)
    print("Connected.")

    run(client, "echo '== docker ps -a ==' && docker ps -a --format '{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}|{{.RunningFor}}'")
    run(client, "echo '== docker ps ==' && docker ps --format '{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}'")
    run(client, "echo '== docker inspect container a58131ed3b93 ==' && docker inspect -f '{{.State.Status}}|{{.Name}}|{{.Config.Image}}' a58131ed3b93 2>&1 || true")
    run(client, "echo '== docker inspect game54 project ==' && docker inspect -f '{{.State.Status}}|{{.Name}}|{{.Config.Image}}' $(docker ps -aq --filter 'name=l4d2') 2>&1 || true")
    run(client, "echo '== docker-compose -p game54 ps ==' && cd /home/haki/games/l4d2 && docker-compose -p game54 ps 2>&1 || true")
    run(client, "echo '== ls /home/haki/games/l4d2 ==' && ls -la /home/haki/games/l4d2")
    run(client, "echo '== check container by name l4d2 ==' && docker ps -a --filter 'name=l4d2' --format '{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}'")
    run(client, "echo '== all containers IDs (long) ==' && docker ps -aq --no-trunc")

    client.close()


if __name__ == "__main__":
    main()
