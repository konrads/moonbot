FROM ubuntu:18.04
RUN apt-get update
RUN apt-get install -y curl wget
COPY bootstrap.sh /
COPY *-dashboard-importable.json /
RUN chmod +x /bootstrap.sh
CMD sleep 15 && /bootstrap.sh
