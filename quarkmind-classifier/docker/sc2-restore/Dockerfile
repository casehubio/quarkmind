FROM --platform=linux/amd64 ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV SC2PATH=/opt/StarCraftII

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-pip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN pip3 install --no-cache-dir \
    pysc2 \
    protobuf==3.20.3 \
    mpyq \
    sc2reader

COPY restore_tracker.py /opt/restore_tracker.py

RUN mkdir -p /data/input /data/output

# Mount points:
#   /opt/StarCraftII  — SC2 headless install
#   /data/input       — stripped replays
#   /data/output      — restored replays

ENTRYPOINT ["python3", "/opt/restore_tracker.py"]
CMD ["--input", "/data/input", "--output", "/data/output", "--workers", "4"]
